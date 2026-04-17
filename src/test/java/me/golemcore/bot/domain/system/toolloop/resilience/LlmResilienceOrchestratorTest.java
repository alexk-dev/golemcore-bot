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
        when(suspendedTurnManager.suspend(context, LlmErrorClassifier.UNKNOWN, coldRetry))
                .thenThrow(new IllegalStateException("Delayed actions disabled"));
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of(strategy));

        LlmResilienceOrchestrator.ResilienceOutcome outcome = orchestrator.handle(
                context, new RuntimeException("boom"), LlmErrorClassifier.UNKNOWN, 5, coldRetry);

        LlmResilienceOrchestrator.ResilienceOutcome.Exhausted exhausted = assertInstanceOf(
                LlmResilienceOrchestrator.ResilienceOutcome.Exhausted.class, outcome);
        assertTrue(exhausted.reason().contains("Cold retry scheduling failed"));
        assertTrue(exhausted.reason().contains("Delayed actions disabled"));
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
    void shouldRestoreL4DegradationStateWhenRecoveredCallSucceeds() {
        LlmResilienceOrchestrator orchestrator = orchestrator(List.of());
        List<ToolDefinition> originalTools = new ArrayList<>(List.of(ToolDefinition.simple("search", "Search")));
        context.setModelTier("balanced");
        context.setAvailableTools(new ArrayList<>());
        context.setAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER, "deep");
        context.setAttribute(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS, originalTools);

        orchestrator.recordSuccess(context);

        assertEquals("deep", context.getModelTier());
        assertEquals(originalTools, context.getAvailableTools());
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L4_ORIGINAL_MODEL_TIER));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.RESILIENCE_L4_ORIGINAL_TOOLS));
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
