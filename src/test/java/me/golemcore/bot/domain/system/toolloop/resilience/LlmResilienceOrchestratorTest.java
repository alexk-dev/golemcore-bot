package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmResilienceOrchestratorTest {

    private RuntimeConfig.ResilienceConfig config;
    private NoSleepRetryPolicy retryPolicy;
    private ProviderCircuitBreaker circuitBreaker;
    private RecoveryStrategy strategy;
    private SuspendedTurnManager suspendedTurnManager;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(1)
                .hotRetryBaseDelayMs(1L)
                .hotRetryCapMs(1L)
                .circuitBreakerFailureThreshold(1)
                .circuitBreakerWindowSeconds(60L)
                .circuitBreakerOpenDurationSeconds(120L)
                .coldRetryEnabled(true)
                .build();
        retryPolicy = new NoSleepRetryPolicy();
        circuitBreaker = new ProviderCircuitBreaker(Clock.fixed(Instant.parse("2026-04-16T03:00:00Z"),
                ZoneOffset.UTC), 1, 60, 120);
        strategy = mock(RecoveryStrategy.class);
        suspendedTurnManager = mock(SuspendedTurnManager.class);
        context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.LLM_MODEL, "provider-a");
    }

    @Test
    void shouldUseHotRetryForTransientErrorsWithinBudget() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 0, config);

        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, outcome);
        assertEquals("L1", retryNow.layer());
        orchestrator.pauseBeforeRetry(retryNow);
        assertEquals(1L, retryPolicy.lastSleepMs);
        assertEquals(ProviderCircuitBreaker.State.OPEN, circuitBreaker.getState("provider-a"));
        verify(strategy, never()).isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config);
    }

    @Test
    void shouldUseFirstSuccessfulDegradationStrategyAfterRetryBudget() {
        when(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config)).thenReturn(true);
        when(strategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config))
                .thenReturn(RecoveryStrategy.RecoveryResult.success("degraded"));
        when(strategy.name()).thenReturn("test_strategy");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, config);

        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, outcome);
        assertEquals("L4:test_strategy", retryNow.layer());
        assertEquals("degraded", retryNow.detail());
    }

    @Test
    void shouldUseRouterFallbackBeforeDegradationAfterRetryBudget() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("provider-a")
                .fallbackMode("sequential")
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder()
                        .model("provider-b")
                        .reasoning("low")
                        .build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);
        context.setModelTier("balanced");
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                retryPolicy,
                circuitBreaker,
                new RuntimeConfigRouterFallbackSelector(runtimeConfigService, new java.util.Random(0)),
                List.of(strategy),
                suspendedTurnManager);

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, config);

        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, outcome);
        assertEquals("L2", retryNow.layer());
        assertEquals("provider-b", context.getAttribute(ContextAttributes.LLM_MODEL));
        assertEquals("low", context.getAttribute(ContextAttributes.LLM_REASONING));
        assertEquals("provider-b", context.getAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL));
        verify(strategy, never()).isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config);
    }

    @Test
    void shouldSkipNonApplicableAndFailedStrategiesBeforeColdRetry() {
        RecoveryStrategy failedStrategy = mock(RecoveryStrategy.class);
        when(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config)).thenReturn(false);
        when(failedStrategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config)).thenReturn(true);
        when(failedStrategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config))
                .thenReturn(RecoveryStrategy.RecoveryResult.notApplicable("still too large"));
        when(failedStrategy.name()).thenReturn("failed_strategy");
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config))
                .thenReturn("scheduled");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy, failedStrategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, config);

        LlmResilienceOrchestrator.ResilienceOutcome.Suspended suspended = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Suspended.class, outcome);
        assertEquals("scheduled", suspended.userMessage());
    }

    @Test
    void shouldExhaustWhenColdRetryDisabledAndNoStrategyRecovers() {
        RuntimeConfig.ResilienceConfig noColdRetry = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 5, noColdRetry);

        LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        assertTrue(exhausted.reason().contains("provider-a"));
    }

    @Test
    void shouldTraceL5ExhaustionWhenColdRetryDisabledAndNoStrategyRecovers() {
        RuntimeConfig.ResilienceConfig noColdRetry = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 5, noColdRetry);

        LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        LlmResilienceOrchestrator.ResilienceTraceStep terminalStep = exhausted.traceSteps().getLast();
        assertEquals("L5", terminalStep.layer());
        assertEquals("exhausted", terminalStep.action());
        assertEquals("provider-a", terminalStep.attributes().get("provider"));
    }

    @Test
    void shouldExhaustWhenColdRetrySchedulingFails() {
        RuntimeConfig.ResilienceConfig coldRetry = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(true)
                .build();
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, coldRetry))
                .thenThrow(new IllegalStateException("Delayed actions disabled"));
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 5, coldRetry);

        LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        assertTrue(exhausted.reason().contains("Cold retry scheduling failed"));
        assertTrue(exhausted.reason().contains("Delayed actions disabled"));
    }

    @Test
    void shouldExhaustWithoutSuspendingWhenErrorCodeIsNotTransient() {
        RuntimeConfig.ResilienceConfig coldRetry = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(true)
                .build();
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("bad request"),
                LlmErrorClassifier.LANGCHAIN4J_INVALID_REQUEST, 5, coldRetry);

        LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        assertTrue(exhausted.reason().contains(LlmErrorClassifier.LANGCHAIN4J_INVALID_REQUEST));
        verify(suspendedTurnManager, never())
                .suspend(context, LlmErrorClassifier.LANGCHAIN4J_INVALID_REQUEST, coldRetry);
        LlmResilienceOrchestrator.ResilienceTraceStep terminalStep = exhausted.traceSteps().getLast();
        assertEquals("L5", terminalStep.layer());
        assertEquals("exhausted", terminalStep.action());
        assertEquals(Boolean.TRUE, terminalStep.attributes().get("cold_retry.enabled"));
        assertEquals(Boolean.FALSE, terminalStep.attributes().get("cold_retry.eligible"));
    }

    @Test
    void shouldResolveUnknownProviderWhenContextIsMissingOrModelAttributeIsBlank() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());
        RuntimeConfig.ResilienceConfig exhaustedConfig = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();

        LlmResilienceOrchestrator.ResilienceOutcome nullContext = orchestrator.handle(
                null, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 1, exhaustedConfig);
        AgentContext blankModelContext = AgentContext.builder().build();
        blankModelContext.setAttribute(ContextAttributes.LLM_MODEL, " ");
        LlmResilienceOrchestrator.ResilienceOutcome blankModel = orchestrator.handle(
                blankModelContext, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 1, exhaustedConfig);
        AgentContext nonStringModelContext = AgentContext.builder().build();
        nonStringModelContext.setAttribute(ContextAttributes.LLM_MODEL, 42);
        LlmResilienceOrchestrator.ResilienceOutcome nonStringModel = orchestrator.handle(
                nonStringModelContext, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 1, exhaustedConfig);

        assertTrue(((LlmResilienceOrchestrator.ResilienceOutcome.Exhausted) nullContext).reason()
                .contains("unknown"));
        assertTrue(((LlmResilienceOrchestrator.ResilienceOutcome.Exhausted) blankModel).reason()
                .contains("unknown"));
        assertTrue(((LlmResilienceOrchestrator.ResilienceOutcome.Exhausted) nonStringModel).reason()
                .contains("unknown"));
    }

    @Test
    void shouldRecordSuccessForResolvedProvider() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());
        circuitBreaker.recordFailure("provider-a");
        assertEquals(ProviderCircuitBreaker.State.OPEN, circuitBreaker.getState("provider-a"));

        orchestrator.recordSuccess(context);

        assertEquals(ProviderCircuitBreaker.State.CLOSED, circuitBreaker.getState("provider-a"));
    }

    @Test
    void shouldKeepTurnScopedResilienceStateWhenIntermediateProviderCallSucceeds() {
        RouterFallbackSelector fallbackSelector = mock(RouterFallbackSelector.class);
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                retryPolicy, circuitBreaker, fallbackSelector, List.of(), suspendedTurnManager);
        context.setAttribute(ContextAttributes.RESILIENCE_L4_MODEL_DOWNGRADE_ATTEMPTED, true);
        context.setAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER, "deep");
        context.setAttribute(ContextAttributes.RESILIENCE_L4_TOOL_STRIP_ATTEMPTED, true);
        context.setAttribute(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT, 2);

        orchestrator.recordSuccess(context);

        verify(fallbackSelector, never()).clear(context);
        assertTrue(
                Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L4_MODEL_DOWNGRADE_ATTEMPTED)));
        assertEquals("deep", context.getAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER));
        assertTrue(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L4_TOOL_STRIP_ATTEMPTED)));
        assertEquals(Integer.valueOf(2), context.getAttribute(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT));
    }

    @Test
    void shouldRestoreL4DegradationStateWhenRecoveredCallSucceeds() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());
        List<ToolDefinition> originalTools = new ArrayList<>(List.of(ToolDefinition.simple("search", "Search")));
        context.setModelTier("balanced");
        context.setAvailableTools(new ArrayList<>());
        context.setAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER, "deep");
        context.setAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS, originalTools);

        orchestrator.recordTurnSuccess(context);

        assertEquals("deep", context.getModelTier());
        assertEquals(originalTools, context.getAvailableTools());
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS));
    }

    @Test
    void shouldClearL5ResumeStateWhenRecoveredCallSucceeds() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());
        context.setAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED, true);
        context.setAttribute(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT, 2);
        context.setAttribute(ContextAttributes.RESILIENCE_L5_ERROR_CODE, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT);
        context.setAttribute(ContextAttributes.RESILIENCE_L5_ORIGINAL_PROMPT, "finish the migration");

        orchestrator.recordTurnSuccess(context);

        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_TURN_SUSPENDED));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L5_ERROR_CODE));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L5_ORIGINAL_PROMPT));
    }

    @Test
    void shouldRecordSuccessForUnknownProviderWhenContextIsNull() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());

        orchestrator.recordSuccess(null);

        assertEquals(ProviderCircuitBreaker.State.CLOSED, circuitBreaker.getState("unknown"));
    }

    @Test
    void shouldSkipDegradationAndSuspendWhenCircuitBreakerIsAlreadyOpen() {
        circuitBreaker.recordFailure("provider-a");
        assertEquals(ProviderCircuitBreaker.State.OPEN, circuitBreaker.getState("provider-a"));
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config))
                .thenReturn("suspended");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));
        RuntimeConfig.ResilienceConfig exhaustedL1 = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(true)
                .build();

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 0, exhaustedL1);

        assertInstanceOf(LlmResilienceOrchestrator.ResilienceOutcome.Suspended.class, outcome);
        verify(strategy, never()).isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, exhaustedL1);
        verify(strategy, never()).apply(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, exhaustedL1);
    }

    @Test
    void shouldSkipL1HotRetryWhenCircuitBreakerIsAlreadyOpen() {
        circuitBreaker.recordFailure("provider-a");
        assertEquals(ProviderCircuitBreaker.State.OPEN, circuitBreaker.getState("provider-a"));
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config))
                .thenReturn("suspended");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        // hotRetryMaxAttempts=1 + attempt=0 + transient code would normally trigger L1.
        // The OPEN breaker must veto: hammering a provider in cooldown defeats the
        // breaker.
        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 0, config);

        assertInstanceOf(LlmResilienceOrchestrator.ResilienceOutcome.Suspended.class, outcome);
        assertEquals(-1L, retryPolicy.lastSleepMs);
        verify(strategy, never()).isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config);
    }

    @Test
    void shouldReopenHalfOpenCircuitWhenAdmittedProbeFails() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-04-16T03:00:00Z"));
        ProviderCircuitBreaker halfOpenBreaker = new ProviderCircuitBreaker(mutableClock, 1, 60, 30);
        halfOpenBreaker.recordFailure("provider-a");
        assertEquals(ProviderCircuitBreaker.State.OPEN, halfOpenBreaker.getState("provider-a"));
        mutableClock.plusSeconds(31);
        assertFalse(halfOpenBreaker.isOpen("provider-a"));
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, halfOpenBreaker.getState("provider-a"));

        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                retryPolicy, halfOpenBreaker, List.of(), suspendedTurnManager);
        RuntimeConfig.ResilienceConfig noRetryNoCold = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("probe failed"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 0,
                noRetryNoCold);

        assertInstanceOf(LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        assertEquals(ProviderCircuitBreaker.State.OPEN, halfOpenBreaker.getState("provider-a"));
    }

    @Test
    void shouldTagTerminalColdRetryFailureWhenAttemptsAreExhausted() {
        RuntimeConfig.ResilienceConfig exhaustedConfig = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(true)
                .build();
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, exhaustedConfig))
                .thenThrow(new IllegalStateException("Cold retry attempts exhausted after 4 attempt(s)"));
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, exhaustedConfig);

        LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        assertTrue(exhausted.reason().contains("Cold retry attempts exhausted"));
        assertEquals(Boolean.TRUE, context.getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE));
        assertTrue(((String) context.getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON))
                .contains("Cold retry attempts exhausted"));
    }

    // ==================== Observability: trace step structure ====================

    @Test
    void shouldEmitL1TraceStepWithProviderAndRetryAttributes() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 0, config);

        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, outcome);
        LlmResilienceOrchestrator.ResilienceTraceStep l1Step = retryNow.traceSteps().stream()
                .filter(step -> "L1".equals(step.layer()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected L1 trace step, got " + retryNow.traceSteps()));
        assertEquals("retry_now", l1Step.action());
        assertEquals("provider-a", l1Step.attributes().get("provider"));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, l1Step.attributes().get("llm.error.code"));
        assertEquals(0, l1Step.attributes().get("retry.attempt"));
        assertTrue(l1Step.attributes().get("retry.delay.ms") instanceof Number,
                () -> "Expected retry.delay.ms to be numeric, got " + l1Step.attributes());
    }

    @Test
    void shouldEmitL2TraceStepWithFallbackModelAttributes() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.TierBinding binding = RuntimeConfig.TierBinding.builder()
                .model("provider-a")
                .fallbackMode("sequential")
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder()
                        .model("provider-b")
                        .reasoning("low")
                        .build()))
                .build();
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(binding);
        context.setModelTier("balanced");
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                retryPolicy,
                circuitBreaker,
                new RuntimeConfigRouterFallbackSelector(runtimeConfigService, new java.util.Random(0)),
                List.of(strategy),
                suspendedTurnManager);

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, config);

        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, outcome);
        LlmResilienceOrchestrator.ResilienceTraceStep l2Step = retryNow.traceSteps().stream()
                .filter(step -> "L2".equals(step.layer()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected L2 trace step, got " + retryNow.traceSteps()));
        assertEquals("retry_now", l2Step.action());
        assertEquals("provider-b", l2Step.attributes().get("fallback.model"));
        assertEquals("balanced", l2Step.attributes().get("fallback.tier"));
        assertEquals("sequential", l2Step.attributes().get("fallback.mode"));
    }

    @Test
    void shouldEmitL3StateTransitionTraceStepWhenBreakerFlipsClosedToOpen() {
        // Threshold is 1 — the single failure recorded by the orchestrator itself
        // is enough to trip the breaker and emit a state_transition trace step.
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));
        RuntimeConfig.ResilienceConfig noL1 = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, noL1);

        LlmResilienceOrchestrator.ResilienceTraceStep transitionStep = outcome.traceSteps().stream()
                .filter(step -> "L3".equals(step.layer()) && "state_transition".equals(step.action()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected L3 state_transition step, got "
                        + outcome.traceSteps()));
        assertEquals("provider-a", transitionStep.attributes().get("circuit.provider"));
        assertEquals("failure_recorded", transitionStep.attributes().get("circuit.action"));
        assertEquals("CLOSED", transitionStep.attributes().get("circuit.state.before"));
        assertEquals("OPEN", transitionStep.attributes().get("circuit.state.after"));
    }

    @Test
    void shouldEmitL4TraceStepWithStrategyAttribute() {
        when(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config)).thenReturn(true);
        when(strategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, config))
                .thenReturn(RecoveryStrategy.RecoveryResult.success("tools stripped"));
        when(strategy.name()).thenReturn("tool_strip");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, config);

        LlmResilienceOrchestrator.ResilienceOutcome.RetryNow retryNow = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.RetryNow.class, outcome);
        LlmResilienceOrchestrator.ResilienceTraceStep l4Step = retryNow.traceSteps().stream()
                .filter(step -> "L4".equals(step.layer()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected L4 trace step, got " + retryNow.traceSteps()));
        assertEquals("retry_now", l4Step.action());
        assertEquals("tools stripped", l4Step.detail());
        assertEquals("tool_strip", l4Step.attributes().get("resilience.strategy"));
        assertEquals("provider-a", l4Step.attributes().get("provider"));
    }

    @Test
    void shouldEmitL5SuspendTraceStepWithColdRetryEnabledFlag() {
        RuntimeConfig.ResilienceConfig coldRetry = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(true)
                .build();
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, coldRetry))
                .thenReturn("saved — retry in 2 minutes");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, coldRetry);

        LlmResilienceOrchestrator.ResilienceOutcome.Suspended suspended = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Suspended.class, outcome);
        LlmResilienceOrchestrator.ResilienceTraceStep l5Step = suspended.traceSteps().stream()
                .filter(step -> "L5".equals(step.layer()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected L5 trace step, got " + suspended.traceSteps()));
        assertEquals("suspend", l5Step.action());
        assertEquals("saved — retry in 2 minutes", l5Step.detail());
        assertEquals(Boolean.TRUE, l5Step.attributes().get("cold_retry.enabled"));
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, l5Step.attributes().get("llm.error.code"));
    }

    @Test
    void shouldEmitTraceStepsInCascadeOrderAcrossL1ToL5() {
        RuntimeConfig.ResilienceConfig coldRetry = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(1)
                .hotRetryBaseDelayMs(1L)
                .coldRetryEnabled(true)
                .build();
        when(strategy.isApplicable(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, coldRetry)).thenReturn(true);
        when(strategy.apply(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, coldRetry))
                .thenReturn(RecoveryStrategy.RecoveryResult.notApplicable("skipped"));
        when(strategy.name()).thenReturn("test_strategy");
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, coldRetry))
                .thenReturn("saved");
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        // attempt=1 → past L1 budget, L2 has no fallbacks → L3 bookkeeping → L4
        // declines → L5 suspends.
        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.LANGCHAIN4J_TIMEOUT, 1, coldRetry);

        List<String> layerOrder = outcome.traceSteps().stream()
                .map(LlmResilienceOrchestrator.ResilienceTraceStep::layer)
                .distinct()
                .toList();
        assertTrue(layerOrder.contains("L3"),
                () -> "Expected L3 breaker accounting in cascade, got " + layerOrder);
        assertEquals("L5", layerOrder.getLast(),
                () -> "Expected L5 to be the final cascade layer, got " + layerOrder);
        // Any L3 transitions must precede the L5 terminal action.
        int lastL3Index = -1;
        int firstL5Index = Integer.MAX_VALUE;
        for (int index = 0; index < outcome.traceSteps().size(); index++) {
            String layer = outcome.traceSteps().get(index).layer();
            if ("L3".equals(layer)) {
                lastL3Index = index;
            } else if ("L5".equals(layer)) {
                firstL5Index = Math.min(firstL5Index, index);
            }
        }
        assertTrue(lastL3Index < firstL5Index,
                () -> "Expected all L3 steps to precede L5 in trace, got " + outcome.traceSteps());
    }

    @Test
    void shouldResolveUnknownProviderWhenAttributesMapIsNull() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());
        AgentContext nullAttributesContext = AgentContext.builder().attributes(null).build();
        RuntimeConfig.ResilienceConfig exhaustedConfig = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                nullAttributesContext, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 1, exhaustedConfig);

        assertTrue(((LlmResilienceOrchestrator.ResilienceOutcome.Exhausted) outcome).reason()
                .contains("unknown"));
    }

    private LlmResilienceOrchestrator orchestrator(List<RecoveryStrategy> strategies) {
        return new LlmResilienceOrchestrator(retryPolicy, circuitBreaker, strategies, suspendedTurnManager);
    }

    private static final class NoSleepRetryPolicy extends LlmRetryPolicy {
        private long lastSleepMs = -1L;

        @Override
        public long computeDelay(int attempt, RuntimeConfig.ResilienceConfig config) {
            return 1L;
        }

        @Override
        public void sleep(long delayMs) {
            lastSleepMs = delayMs;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void plusSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
