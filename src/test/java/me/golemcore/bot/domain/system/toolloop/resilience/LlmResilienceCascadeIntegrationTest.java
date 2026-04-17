package me.golemcore.bot.domain.system.toolloop.resilience;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import me.golemcore.bot.adapter.outbound.llm.Langchain4jAdapter;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.InternalTurnService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.system.OutgoingResponsePreparationSystem;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.HistoryWriter;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.port.outbound.ModelConfigPort;
import me.golemcore.bot.port.outbound.ToolArtifactReadPort;
import me.golemcore.bot.port.outbound.ToolRuntimeSettingsPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmResilienceCascadeIntegrationTest {

    private static final String PRIMARY_MODEL = "provider-primary";
    private static final String FALLBACK_MODEL = "provider-fallback";
    private static final String FALLBACK_MODEL_2 = "provider-fallback-2";
    private static final String ERROR_CODE = LlmErrorClassifier.LANGCHAIN4J_RATE_LIMIT;
    private static final String GENERIC_AI_FAILURE = "Failed to get a response from the AI model. Please try again.";
    private static final Instant START = Instant.parse("2026-04-16T04:00:00Z");
    private static final int COLD_RETRY_MAX_ATTEMPTS = 3;
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 2;
    private static final long CIRCUIT_BREAKER_OPEN_DURATION_SECONDS = 3600L;

    @Test
    void shouldRunFullCascadeFromAdapterRetriesThroughL1ToL5AndScheduleColdRetry() {
        CascadeHarness harness = buildHarness(List.of(RuntimeConfig.TierFallback.builder()
                .model(FALLBACK_MODEL)
                .reasoning("low")
                .build()), true, true);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertEquals(4, result.llmCalls());
        assertEquals(List.of(PRIMARY_MODEL, PRIMARY_MODEL, FALLBACK_MODEL, FALLBACK_MODEL),
                harness.adapter().requestModels());
        verify(harness.chatModel(), times(16)).chat(anyChatMessages());
        assertEquals(List.of(
                "adapter:5000",
                "adapter:10000",
                "adapter:20000",
                "L1:250"),
                harness.timeline().subList(0, 4));
        assertEquals(12, harness.adapter().retryBackoffs().size());
        assertEquals(List.of(250L), harness.retryPolicy().sleepDelays());
        assertEquals(1, harness.degradation().applications());
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(FALLBACK_MODEL));
        assertTrue(Boolean.TRUE.equals(
                harness.context().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));

        DelayedSessionAction action = captureScheduledAction(harness.delayedActionService());
        assertEquals(DelayedActionKind.RETRY_LLM_TURN, action.getKind());
        assertEquals(DelayedActionDeliveryMode.INTERNAL_TURN, action.getDeliveryMode());
        assertEquals(action.getCreatedAt().plusSeconds(120), action.getRunAt());
        assertEquals(ERROR_CODE, action.getPayload().get("errorCode"));
        assertEquals("run resilience cascade", action.getPayload().get("originalPrompt"));
        assertEquals(0, action.getPayload().get("resumeAttempt"));

        List<String> notices = progressNotices(harness.turnProgressService(), harness.context());
        assertTrue(notices.stream().anyMatch(text -> text.contains("Retrying the same LLM model")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Switching to fallback model "
                + FALLBACK_MODEL)));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Circuit breaker moved CLOSED -> OPEN")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Applying LLM degradation: one_shot")));
        assertTrue(notices.stream().anyMatch(text -> text.startsWith("Turn suspended:")));
        assertNull(harness.context().getAttribute(ContextAttributes.LLM_RESPONSE));
        OutgoingResponse outgoing = prepareOutgoingResponse(harness);
        assertNotNull(outgoing);
        assertTrue(outgoing.getText().contains("retry automatically in 2 minute(s)"));
        assertFalse(outgoing.getText().contains(GENERIC_AI_FAILURE));
    }

    @Test
    void shouldRunFullCascadeWithoutFallbackModelsAndScheduleColdRetryFromOpenCircuit() {
        CascadeHarness harness = buildHarness(List.of(), true, true);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        assertEquals(List.of(PRIMARY_MODEL, PRIMARY_MODEL), harness.adapter().requestModels());
        verify(harness.chatModel(), times(8)).chat(anyChatMessages());
        assertEquals(List.of(
                "adapter:5000",
                "adapter:10000",
                "adapter:20000",
                "L1:250"),
                harness.timeline().subList(0, 4));
        assertEquals(6, harness.adapter().retryBackoffs().size());
        assertEquals(List.of(250L), harness.retryPolicy().sleepDelays());
        assertEquals(1, harness.degradation().applications());
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        assertTrue(Boolean.TRUE.equals(
                harness.context().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));

        DelayedSessionAction action = captureScheduledAction(harness.delayedActionService());
        assertEquals(DelayedActionKind.RETRY_LLM_TURN, action.getKind());
        assertEquals(DelayedActionDeliveryMode.INTERNAL_TURN, action.getDeliveryMode());
        assertEquals(action.getCreatedAt().plusSeconds(120), action.getRunAt());
        assertEquals(LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN, action.getPayload().get("errorCode"));

        List<String> notices = progressNotices(harness.turnProgressService(), harness.context());
        assertTrue(notices.stream().anyMatch(text -> text.contains("Retrying the same LLM model")));
        assertFalse(notices.stream().anyMatch(text -> text.contains("Switching to fallback model")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Applying LLM degradation: one_shot")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Circuit breaker is open for "
                + PRIMARY_MODEL)));
        assertTrue(notices.stream().anyMatch(text -> text.startsWith("Turn suspended:")));
        OutgoingResponse outgoing = prepareOutgoingResponse(harness);
        assertNotNull(outgoing);
        assertTrue(outgoing.getText().contains("retry automatically in 2 minute(s)"));
        assertFalse(outgoing.getText().contains(GENERIC_AI_FAILURE));
    }

    @Test
    void shouldKeepL1ToL5DisabledWhenResilienceIsOff() {
        CascadeHarness harness = buildHarness(List.of(RuntimeConfig.TierFallback.builder()
                .model(FALLBACK_MODEL)
                .reasoning("low")
                .build()), false, false);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(List.of(PRIMARY_MODEL), harness.adapter().requestModels());
        verify(harness.chatModel(), times(4)).chat(anyChatMessages());
        assertEquals(List.of("adapter:5000", "adapter:10000", "adapter:20000"),
                harness.timeline());
        assertTrue(harness.retryPolicy().sleepDelays().isEmpty());
        assertEquals(0, harness.degradation().applications());
        assertTrue(harness.circuitBreaker().snapshotStates().isEmpty());
        assertFalse(Boolean.TRUE.equals(
                harness.context().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));
        verify(harness.delayedActionService(), never()).schedule(any(DelayedSessionAction.class));
        verify(harness.turnProgressService(), never()).publishSummary(
                eq(harness.context()), anyString(), any());
        verifyNoInteractions(harness.orchestrator());

        OutgoingResponse outgoing = prepareOutgoingResponse(harness);
        assertNotNull(outgoing);
        assertEquals(GENERIC_AI_FAILURE, outgoing.getText());
    }

    // ==================== Group A — recovery paths ====================

    @Test
    void shouldRecoverAtL1WhenHotRetrySucceeds() {
        RuntimeException rateLimit = new RuntimeException("rate_limit exceeded");
        ChatModel scripted = scriptedChatModel(
                rateLimit, rateLimit, rateLimit, rateLimit,
                okChatResponse());
        CascadeHarness harness = buildHarness(List.of(), true, true, scripted);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        assertEquals(List.of(PRIMARY_MODEL, PRIMARY_MODEL), harness.adapter().requestModels());
        LlmResponse response = harness.context().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(response);
        assertEquals("ok", response.getContent());
        assertEquals(List.of(250L), harness.retryPolicy().sleepDelays());
        assertEquals(ProviderCircuitBreaker.State.CLOSED,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        assertFalse(Boolean.TRUE.equals(
                harness.context().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));
        verify(harness.delayedActionService(), never()).schedule(any(DelayedSessionAction.class));
    }

    @Test
    void shouldRecoverAtL2WhenFallbackModelSucceeds() {
        RuntimeException rateLimit = new RuntimeException("rate_limit exceeded");
        // Primary trips breaker after 2 hard failures (4 attempts each). Fallback
        // succeeds on first try, so the total script is:
        // primary x8 failures → L2 fallback invoked → fallback x1 success.
        Object[] script = new Object[9];
        for (int index = 0; index < 8; index++) {
            script[index] = rateLimit;
        }
        script[8] = okChatResponse();
        ChatModel scripted = scriptedChatModel(script);
        CascadeHarness harness = buildHarness(
                List.of(RuntimeConfig.TierFallback.builder().model(FALLBACK_MODEL).reasoning("low").build()),
                true, true, scripted);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertTrue(result.finalAnswerReady());
        assertEquals(List.of(PRIMARY_MODEL, PRIMARY_MODEL, FALLBACK_MODEL),
                harness.adapter().requestModels());
        assertEquals(FALLBACK_MODEL, harness.context().getAttribute(ContextAttributes.LLM_MODEL));
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        assertEquals(ProviderCircuitBreaker.State.CLOSED,
                harness.circuitBreaker().getState(FALLBACK_MODEL));
        LlmResponse response = harness.context().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(response);
        assertEquals("ok", response.getContent());
        verify(harness.delayedActionService(), never()).schedule(any(DelayedSessionAction.class));
    }

    @Test
    void shouldRecoverAtL4WhenDegradationSucceedsAndSubsequentCallWorks() {
        RuntimeException rateLimit = new RuntimeException("rate_limit exceeded");
        // Raised failure threshold keeps the breaker CLOSED so L4 degradation runs
        // without being fast-failed by the open-circuit preflight on the next call.
        // Primary fails 4 times (initial + 3 adapter retries), L1 triggers hot
        // retry, primary fails 4 more times (failure count 2, threshold 10 → still
        // CLOSED), L4 degradation fires, next adapter call succeeds on first try.
        Object[] script = new Object[9];
        for (int index = 0; index < 8; index++) {
            script[index] = rateLimit;
        }
        script[8] = okChatResponse();
        ChatModel scripted = scriptedChatModel(script);
        CascadeHarness harness = buildHarness(List.of(), true, true, scripted, 10);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertTrue(result.finalAnswerReady());
        assertEquals(Boolean.TRUE, harness.context().getAttribute("test.l4.degraded"));
        assertEquals(1, harness.degradation().applications());
        LlmResponse response = harness.context().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(response);
        assertEquals("ok", response.getContent());
        verify(harness.delayedActionService(), never()).schedule(any(DelayedSessionAction.class));
    }

    // ==================== Group B — non-transient cold retry bypass
    // ====================

    @Test
    void shouldBypassColdRetryForNonTransientError() {
        InvalidRequestException invalidRequest = new InvalidRequestException("model rejected the request");
        CascadeHarness harness = buildHarness(List.of(), true, true,
                alwaysThrowingChatModel(invalidRequest));

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        // Non-transient errors skip the adapter's rate-limit retry loop entirely;
        // each LLM call is a single chat invocation regardless of L4 replays.
        assertEquals(harness.adapter().requestModels().size(),
                org.mockito.Mockito.mockingDetails(harness.chatModel()).getInvocations().size());
        // L5 cold retry is gated by transient classification — must NOT be scheduled.
        verify(harness.delayedActionService(), never()).schedule(any(DelayedSessionAction.class));
        assertFalse(Boolean.TRUE.equals(
                harness.context().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));
        OutgoingResponse outgoing = prepareOutgoingResponse(harness);
        assertNotNull(outgoing);
        assertFalse(outgoing.getText().contains("retry automatically in 2 minute(s)"));
        assertEquals(GENERIC_AI_FAILURE, outgoing.getText());
    }

    // ==================== Group C — L5 edge cases ====================

    @Test
    void shouldMarkTerminalFailureWhenResumeAttemptsExhausted() {
        CascadeHarness harness = buildHarness(List.of(), true, true);
        harness.context().setAttribute(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT,
                COLD_RETRY_MAX_ATTEMPTS);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertEquals(Boolean.TRUE,
                harness.context().getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE));
        String reason = harness.context().getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON);
        assertNotNull(reason);
        assertTrue(reason.toLowerCase(java.util.Locale.ROOT).contains("exhausted"),
                () -> "Expected terminal reason to mention exhaustion, but was: " + reason);
        OutgoingResponse outgoing = prepareOutgoingResponse(harness);
        assertNotNull(outgoing);
        assertEquals(GENERIC_AI_FAILURE, outgoing.getText());
    }

    @Test
    void shouldExhaustWhenDelayedActionsServiceRejectsSchedule() {
        CascadeHarness harness = buildHarness(List.of(), true, true);
        org.mockito.Mockito.doThrow(new IllegalStateException("Delayed actions disabled"))
                .when(harness.delayedActionService()).schedule(any(DelayedSessionAction.class));

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertEquals(Boolean.TRUE,
                harness.context().getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE));
        String reason = harness.context().getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON);
        assertNotNull(reason);
        assertTrue(reason.contains("Cold retry scheduling failed"),
                () -> "Expected terminal reason to cite cold retry scheduling failure, but was: " + reason);
        assertTrue(reason.contains("Delayed actions disabled"),
                () -> "Expected terminal reason to cite scheduler message, but was: " + reason);
        OutgoingResponse outgoing = prepareOutgoingResponse(harness);
        assertNotNull(outgoing);
        assertEquals(GENERIC_AI_FAILURE, outgoing.getText());
    }

    // ==================== Group D — circuit breaker states ====================

    @Test
    void shouldFastFailWithoutCallingProviderWhenCircuitAlreadyOpen() {
        // No fallbacks: preflight open circuit fast-fails directly into L5.
        CascadeHarness harness = buildHarness(List.of(), true, true);
        for (int index = 0; index < CIRCUIT_BREAKER_FAILURE_THRESHOLD; index++) {
            harness.circuitBreaker().recordFailure(PRIMARY_MODEL);
        }
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertTrue(harness.adapter().requestModels().isEmpty(),
                () -> "Expected preflight to short-circuit before any provider call, but saw: "
                        + harness.adapter().requestModels());
        verify(harness.chatModel(), never()).chat(anyChatMessages());
        assertTrue(Boolean.TRUE.equals(
                harness.context().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));
        DelayedSessionAction action = captureScheduledAction(harness.delayedActionService());
        assertEquals(LlmErrorClassifier.PROVIDER_CIRCUIT_OPEN, action.getPayload().get("errorCode"));
    }

    @Test
    void shouldCloseCircuitWhenHalfOpenProbeSucceeds() {
        ChatModel scripted = scriptedChatModel(okChatResponse());
        CascadeHarness harness = buildHarness(List.of(), true, true, scripted);
        for (int index = 0; index < CIRCUIT_BREAKER_FAILURE_THRESHOLD; index++) {
            harness.circuitBreaker().recordFailure(PRIMARY_MODEL);
        }
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        // Advance past the OPEN duration so the next availability check flips
        // the breaker into HALF_OPEN and admits a single probe.
        harness.clock().advance(Duration.ofSeconds(CIRCUIT_BREAKER_OPEN_DURATION_SECONDS + 1));

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertTrue(result.finalAnswerReady());
        assertEquals(ProviderCircuitBreaker.State.CLOSED,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        LlmResponse response = harness.context().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(response);
        assertEquals("ok", response.getContent());
    }

    @Test
    void shouldReopenCircuitWhenHalfOpenProbeFails() {
        CascadeHarness harness = buildHarness(List.of(), true, true);
        for (int index = 0; index < CIRCUIT_BREAKER_FAILURE_THRESHOLD; index++) {
            harness.circuitBreaker().recordFailure(PRIMARY_MODEL);
        }
        harness.clock().advance(Duration.ofSeconds(CIRCUIT_BREAKER_OPEN_DURATION_SECONDS + 1));

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        // No infinite retry loop — adapter is invoked for the single probe only.
        assertEquals(List.of(PRIMARY_MODEL), harness.adapter().requestModels());
    }

    // ==================== Group E — router fallback depth ====================

    @Test
    void shouldIterateThroughMultipleFallbacksBeforeCascadeExhaustion() {
        CascadeHarness harness = buildHarness(List.of(
                RuntimeConfig.TierFallback.builder().model(FALLBACK_MODEL).reasoning("low").build(),
                RuntimeConfig.TierFallback.builder().model(FALLBACK_MODEL_2).reasoning("low").build()),
                true, true);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertFalse(result.finalAnswerReady());
        List<String> requestModels = harness.adapter().requestModels();
        assertTrue(requestModels.contains(PRIMARY_MODEL),
                () -> "Expected primary model to be invoked, got " + requestModels);
        assertTrue(requestModels.contains(FALLBACK_MODEL),
                () -> "Expected first fallback to be invoked, got " + requestModels);
        assertTrue(requestModels.contains(FALLBACK_MODEL_2),
                () -> "Expected second fallback to be invoked, got " + requestModels);
        int primaryIndex = requestModels.indexOf(PRIMARY_MODEL);
        int fallbackIndex = requestModels.indexOf(FALLBACK_MODEL);
        int fallback2Index = requestModels.indexOf(FALLBACK_MODEL_2);
        assertTrue(primaryIndex < fallbackIndex && fallbackIndex < fallback2Index,
                () -> "Expected models in order primary,fallback,fallback2, got " + requestModels);
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(PRIMARY_MODEL));
        // Fallback models may or may not breach the failure threshold depending on
        // how many times each one is retried before the cascade moves on. All three
        // must at least be recorded, and FALLBACK_MODEL_2 (the last model tried)
        // should be OPEN once L2 exhaustion + L4 replay both fire on it.
        assertTrue(harness.circuitBreaker().snapshotStates().containsKey(FALLBACK_MODEL));
        assertEquals(ProviderCircuitBreaker.State.OPEN,
                harness.circuitBreaker().getState(FALLBACK_MODEL_2));
        verify(harness.delayedActionService(), times(1)).schedule(any(DelayedSessionAction.class));
    }

    // ==================== Group F — observability / tracing ====================

    @Test
    void shouldPropagateExhaustedReasonIntoLlmErrorForCascadeFailures() {
        InvalidRequestException invalidRequest = new InvalidRequestException("model rejected the request");
        CascadeHarness harness = buildHarness(List.of(), true, true,
                alwaysThrowingChatModel(invalidRequest));

        harness.system().processTurn(harness.context());

        String llmError = harness.context().getAttribute(ContextAttributes.LLM_ERROR);
        assertNotNull(llmError,
                "Exhausted cascade must propagate the orchestrator reason into LLM_ERROR for downstream systems");
        assertTrue(llmError.contains("unavailable after all recovery attempts")
                || llmError.contains("LLM provider"),
                () -> "Expected LLM_ERROR to carry orchestrator-authoritative reason, but was: " + llmError);
        assertTrue(llmError.contains(LlmErrorClassifier.LANGCHAIN4J_INVALID_REQUEST),
                () -> "Expected LLM_ERROR to include the originating error code, but was: " + llmError);
        String errorCode = harness.context().getAttribute(ContextAttributes.LLM_ERROR_CODE);
        assertEquals(LlmErrorClassifier.LANGCHAIN4J_INVALID_REQUEST, errorCode);
    }

    @Test
    void shouldTagResilienceProgressMetadataWithKindAndLayerForEachStage() {
        CascadeHarness harness = buildHarness(List.of(RuntimeConfig.TierFallback.builder()
                .model(FALLBACK_MODEL)
                .reasoning("low")
                .build()), true, true);

        harness.system().processTurn(harness.context());

        List<Map<String, Object>> metadataCalls = captureProgressMetadata(harness);
        assertFalse(metadataCalls.isEmpty(),
                "Expected at least one publishSummary call carrying resilience metadata");
        for (Map<String, Object> metadata : metadataCalls) {
            assertEquals("llm_resilience", metadata.get("kind"),
                    () -> "Every resilience progress summary must be tagged kind=llm_resilience, got " + metadata);
            assertNotNull(metadata.get("resilience.layer"),
                    () -> "Resilience progress metadata must include resilience.layer, got " + metadata);
            assertNotNull(metadata.get("resilience.action"),
                    () -> "Resilience progress metadata must include resilience.action, got " + metadata);
        }
        List<String> layersInOrder = metadataCalls.stream()
                .map(metadata -> (String) metadata.get("resilience.layer"))
                .toList();
        assertTrue(layersInOrder.contains("L1"),
                () -> "Expected L1 notice before cascade escalation, got layers " + layersInOrder);
        assertTrue(layersInOrder.contains("L5"),
                () -> "Expected terminal L5 notice in cascade, got layers " + layersInOrder);
        assertTrue(layersInOrder.indexOf("L1") < layersInOrder.lastIndexOf("L5"),
                () -> "Expected L1 notices to precede the final L5 notice, got " + layersInOrder);
    }

    @Test
    void shouldEmitL3StateTransitionMetadataWithBreakerBeforeAndAfterStates() {
        CascadeHarness harness = buildHarness(List.of(), true, true);

        harness.system().processTurn(harness.context());

        List<Map<String, Object>> metadataCalls = captureProgressMetadata(harness);
        Map<String, Object> stateTransition = metadataCalls.stream()
                .filter(metadata -> "L3".equals(metadata.get("resilience.layer"))
                        && "state_transition".equals(metadata.get("resilience.action")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected L3 state_transition progress metadata, got " + metadataCalls));
        assertEquals("CLOSED", stateTransition.get("circuit.state.before"));
        assertEquals("OPEN", stateTransition.get("circuit.state.after"));
        assertEquals(PRIMARY_MODEL, stateTransition.get("circuit.provider"));
    }

    @Test
    void shouldCleanUpResilienceContextAttributesWhenFallbackRecoverySucceeds() {
        RuntimeException rateLimit = new RuntimeException("rate_limit exceeded");
        Object[] script = new Object[9];
        for (int index = 0; index < 8; index++) {
            script[index] = rateLimit;
        }
        script[8] = okChatResponse();
        ChatModel scripted = scriptedChatModel(script);
        CascadeHarness harness = buildHarness(
                List.of(RuntimeConfig.TierFallback.builder().model(FALLBACK_MODEL).reasoning("low").build()),
                true, true, scripted);

        ToolLoopTurnResult result = harness.system().processTurn(harness.context());

        assertTrue(result.finalAnswerReady(),
                "Fallback must recover so the success cleanup path runs");
        // recordTurnSuccess should strip L5 resume state so the next turn starts clean.
        assertFalse(harness.context().getAttributes()
                .containsKey(ContextAttributes.RESILIENCE_TURN_SUSPENDED));
        assertFalse(harness.context().getAttributes()
                .containsKey(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT));
        assertFalse(harness.context().getAttributes()
                .containsKey(ContextAttributes.RESILIENCE_L5_ORIGINAL_PROMPT));
        assertFalse(harness.context().getAttributes()
                .containsKey(ContextAttributes.RESILIENCE_L5_ERROR_CODE));
        // Terminal-failure markers must never leak from a recovered turn.
        assertFalse(harness.context().getAttributes()
                .containsKey(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE));
        assertFalse(harness.context().getAttributes()
                .containsKey(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON));
    }

    private List<Map<String, Object>> captureProgressMetadata(CascadeHarness harness) {
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Map<String, Object>> metadataCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(harness.turnProgressService(), atLeastOnce()).publishSummary(eq(harness.context()),
                textCaptor.capture(), metadataCaptor.capture());
        return metadataCaptor.getAllValues();
    }

    // ==================== Group G — cancellation integration ====================

    // SKIPPED: shouldCancelScheduledRetryWhenUserSendsNonInternalMessage
    // Wiring a real DelayedSessionActionService requires standing up a full
    // DelayedActionRegistryPort (load/save of a persisted action list), plus
    // the runtime-config + clock plumbing that service depends on. That's
    // substantially heavier than the rest of the cascade integration coverage,
    // and cancelOnUserActivity semantics are already covered by dedicated
    // DelayedSessionActionServiceTest scenarios. Left out of this integration
    // suite to keep harness composition lean.

    private CascadeHarness buildHarness(List<RuntimeConfig.TierFallback> fallbacks,
            boolean resilienceEnabled, boolean useRealOrchestrator) {
        return buildHarness(fallbacks, resilienceEnabled, useRealOrchestrator, null,
                CIRCUIT_BREAKER_FAILURE_THRESHOLD);
    }

    private CascadeHarness buildHarness(List<RuntimeConfig.TierFallback> fallbacks,
            boolean resilienceEnabled, boolean useRealOrchestrator, ChatModel chatModelOverride) {
        return buildHarness(fallbacks, resilienceEnabled, useRealOrchestrator, chatModelOverride,
                CIRCUIT_BREAKER_FAILURE_THRESHOLD);
    }

    private CascadeHarness buildHarness(List<RuntimeConfig.TierFallback> fallbacks,
            boolean resilienceEnabled, boolean useRealOrchestrator, ChatModel chatModelOverride,
            int circuitBreakerFailureThreshold) {
        MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
        RuntimeConfig.ResilienceConfig resilienceConfig = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(1)
                .hotRetryBaseDelayMs(250L)
                .hotRetryCapMs(250L)
                .l2ProviderFallbackMaxAttempts(5)
                .coldRetryEnabled(true)
                .coldRetryMaxAttempts(COLD_RETRY_MAX_ATTEMPTS)
                .build();
        RuntimeConfigService runtimeConfigService = runtimeConfigService(resilienceEnabled,
                resilienceConfig, fallbacks);
        ModelSelectionService modelSelectionService = modelSelectionService();
        List<String> timeline = new ArrayList<>();
        ChatModel chatModel = chatModelOverride != null ? chatModelOverride : alwaysRateLimitedChatModel();
        ClockAdvancingLangchain4jAdapter adapter = adapter(runtimeConfigService, clock, timeline, chatModel);
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        when(delayedActionService.schedule(any(DelayedSessionAction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProviderCircuitBreaker circuitBreaker = new ProviderCircuitBreaker(clock,
                circuitBreakerFailureThreshold, 3600, CIRCUIT_BREAKER_OPEN_DURATION_SECONDS);
        RecordingRetryPolicy retryPolicy = new RecordingRetryPolicy(clock, timeline);
        OneShotRecoveryStrategy degradation = new OneShotRecoveryStrategy();
        LlmResilienceOrchestrator realOrchestrator = new LlmResilienceOrchestrator(
                retryPolicy,
                circuitBreaker,
                new RuntimeConfigRouterFallbackSelector(runtimeConfigService),
                List.of(degradation),
                new SuspendedTurnManager(delayedActionService, clock));
        LlmResilienceOrchestrator orchestrator = useRealOrchestrator ? realOrchestrator : spy(realOrchestrator);
        TurnProgressService turnProgressService = mock(TurnProgressService.class);
        AgentContext context = context(clock);
        DefaultToolLoopSystem system = DefaultToolLoopSystem.builder()
                .llmPort(adapter)
                .toolExecutor(mock(ToolExecutorPort.class))
                .historyWriter(mock(HistoryWriter.class))
                .viewBuilder((agentContext, targetModel) -> ConversationView.ofMessages(agentContext.getMessages()))
                .turnSettings(ToolRuntimeSettingsPort.defaultTurnSettings())
                .settings(ToolRuntimeSettingsPort.defaultToolLoopSettings())
                .modelSelectionService(modelSelectionService)
                .runtimeConfigService(runtimeConfigService)
                .turnProgressService(turnProgressService)
                .resilienceOrchestrator(orchestrator)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();
        return new CascadeHarness(system, context, adapter, chatModel, retryPolicy, degradation,
                circuitBreaker, delayedActionService, turnProgressService, orchestrator, timeline, clock);
    }

    private RuntimeConfigService runtimeConfigService(boolean resilienceEnabled,
            RuntimeConfig.ResilienceConfig resilienceConfig, List<RuntimeConfig.TierFallback> fallbacks) {
        RuntimeConfigService service = mock(RuntimeConfigService.class);
        when(service.getTurnMaxLlmCalls()).thenReturn(20);
        when(service.getTurnMaxToolExecutions()).thenReturn(10);
        when(service.getTurnDeadline()).thenReturn(Duration.ofHours(6));
        when(service.isTurnAutoRetryEnabled()).thenReturn(false);
        when(service.getTurnAutoRetryMaxAttempts()).thenReturn(0);
        when(service.getTurnAutoRetryBaseDelayMs()).thenReturn(1L);
        when(service.isResilienceEnabled()).thenReturn(resilienceEnabled);
        when(service.getResilienceConfig()).thenReturn(resilienceConfig);
        when(service.getTemperatureForModel(anyString(), anyString())).thenReturn(0.7);
        when(service.getLlmProviderConfig("provider")).thenReturn(RuntimeConfig.LlmProviderConfig.builder()
                .apiType("openai")
                .legacyApi(true)
                .build());
        when(service.getModelTierBinding("balanced")).thenReturn(RuntimeConfig.TierBinding.builder()
                .model(PRIMARY_MODEL)
                .fallbackMode(FallbackModes.SEQUENTIAL)
                .fallbacks(fallbacks)
                .build());
        return service;
    }

    private ModelSelectionService modelSelectionService() {
        ModelSelectionService service = mock(ModelSelectionService.class);
        when(service.resolveForTier(eq("balanced")))
                .thenReturn(new ModelSelectionService.ModelSelection(PRIMARY_MODEL, null));
        when(service.resolveRouterFallbackSelection(eq("balanced"), eq(FALLBACK_MODEL), eq("low")))
                .thenReturn(new ModelSelectionService.ModelSelection(FALLBACK_MODEL, "low"));
        when(service.resolveRouterFallbackSelection(eq("balanced"), eq(FALLBACK_MODEL_2), eq("low")))
                .thenReturn(new ModelSelectionService.ModelSelection(FALLBACK_MODEL_2, "low"));
        when(service.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);
        return service;
    }

    private ClockAdvancingLangchain4jAdapter adapter(RuntimeConfigService runtimeConfigService,
            MutableClock clock, List<String> timeline, ChatModel chatModel) {
        ModelConfigPort modelConfig = mock(ModelConfigPort.class);
        when(modelConfig.supportsTemperature(anyString())).thenReturn(true);
        when(modelConfig.supportsVision(anyString())).thenReturn(false);
        when(modelConfig.getProvider(anyString())).thenReturn("provider");
        when(modelConfig.isReasoningRequired(anyString())).thenReturn(false);
        when(modelConfig.getAllModels()).thenReturn(Map.of());
        ToolArtifactReadPort artifactReadPort = mock(ToolArtifactReadPort.class);
        ClockAdvancingLangchain4jAdapter adapter = new ClockAdvancingLangchain4jAdapter(
                runtimeConfigService, modelConfig, artifactReadPort, clock, timeline);
        ReflectionTestUtils.setField(adapter, "initialized", true);
        ReflectionTestUtils.setField(adapter, "currentModel", PRIMARY_MODEL);
        ReflectionTestUtils.setField(adapter, "chatModel", chatModel);
        @SuppressWarnings("unchecked")
        Map<String, ChatModel> tierScopedModels = (Map<String, ChatModel>) ReflectionTestUtils
                .getField(adapter, "tierScopedChatModels");
        assertNotNull(tierScopedModels);
        tierScopedModels.put("balanced:" + PRIMARY_MODEL + "::0.7", chatModel);
        tierScopedModels.put("balanced:" + FALLBACK_MODEL + ":low:0.7", chatModel);
        tierScopedModels.put("balanced:" + FALLBACK_MODEL_2 + ":low:0.7", chatModel);
        return adapter;
    }

    private ChatModel alwaysRateLimitedChatModel() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(anyChatMessages()))
                .thenThrow(new RuntimeException("rate_limit exceeded"));
        return chatModel;
    }

    private ChatModel alwaysThrowingChatModel(Throwable throwable) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(anyChatMessages()))
                .thenThrow(throwable);
        return chatModel;
    }

    /**
     * Returns a ChatModel whose consecutive {@code chat(List)} invocations play
     * back the provided script. Scripted items may be either {@link ChatResponse}
     * (returned) or {@link Throwable} (thrown). When the script is exhausted, the
     * last item is replayed so callers may keep invoking without an
     * {@code IllegalStateException}.
     */
    private ChatModel scriptedChatModel(Object... scriptedResponsesOrExceptions) {
        if (scriptedResponsesOrExceptions == null || scriptedResponsesOrExceptions.length == 0) {
            throw new IllegalArgumentException("At least one scripted item is required");
        }
        ChatModel chatModel = mock(ChatModel.class);
        List<Object> script = new ArrayList<>(List.of(scriptedResponsesOrExceptions));
        java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger(0);
        when(chatModel.chat(anyChatMessages())).thenAnswer(invocation -> {
            int index = Math.min(cursor.getAndIncrement(), script.size() - 1);
            Object item = script.get(index);
            if (item instanceof Throwable throwable) {
                if (throwable instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (throwable instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(throwable);
            }
            return item;
        });
        return chatModel;
    }

    private ChatResponse okChatResponse() {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .build();
    }

    private AgentContext context(Clock clock) {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(List.of(Message.builder()
                        .role("user")
                        .content("run resilience cascade")
                        .timestamp(clock.instant())
                        .build()))
                .build();
        return AgentContext.builder()
                .session(session)
                .messages(session.getMessages())
                .modelTier("balanced")
                .maxIterations(1)
                .currentIteration(0)
                .build();
    }

    private DelayedSessionAction captureScheduledAction(DelayedSessionActionService delayedActionService) {
        ArgumentCaptor<DelayedSessionAction> actionCaptor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(delayedActionService).schedule(actionCaptor.capture());
        DelayedSessionAction action = actionCaptor.getValue();
        assertNotNull(action);
        return action;
    }

    private List<String> progressNotices(TurnProgressService turnProgressService, AgentContext context) {
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(turnProgressService, atLeastOnce()).publishSummary(eq(context), textCaptor.capture(), any());
        return textCaptor.getAllValues();
    }

    private OutgoingResponse prepareOutgoingResponse(CascadeHarness harness) {
        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage("system.error.llm")).thenReturn(GENERIC_AI_FAILURE);
        OutgoingResponsePreparationSystem system = new OutgoingResponsePreparationSystem(
                preferencesService,
                mock(ModelSelectionService.class),
                mock(RuntimeConfigService.class),
                mock(InternalTurnService.class));
        system.process(harness.context());
        return harness.context().getAttribute(ContextAttributes.OUTGOING_RESPONSE);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> anyChatMessages() {
        return (List<ChatMessage>) any(List.class);
    }

    private record CascadeHarness(DefaultToolLoopSystem system, AgentContext context,
            ClockAdvancingLangchain4jAdapter adapter, ChatModel chatModel,
            RecordingRetryPolicy retryPolicy, OneShotRecoveryStrategy degradation,
            ProviderCircuitBreaker circuitBreaker, DelayedSessionActionService delayedActionService,
            TurnProgressService turnProgressService, LlmResilienceOrchestrator orchestrator,
            List<String> timeline, MutableClock clock) {
    }

    private static final class ClockAdvancingLangchain4jAdapter extends Langchain4jAdapter {

        private final MutableClock clock;
        private final List<String> timeline;
        private final List<Long> retryBackoffs = new ArrayList<>();
        private final List<String> requestModels = new ArrayList<>();

        private ClockAdvancingLangchain4jAdapter(RuntimeConfigService runtimeConfigService,
                ModelConfigPort modelConfig, ToolArtifactReadPort toolArtifactReadPort,
                MutableClock clock, List<String> timeline) {
            super(runtimeConfigService, modelConfig, toolArtifactReadPort);
            this.clock = clock;
            this.timeline = timeline;
        }

        @Override
        public CompletableFuture<LlmResponse> chat(me.golemcore.bot.domain.model.LlmRequest request) {
            requestModels.add(request.getModel());
            return super.chat(request);
        }

        @Override
        protected void sleepBeforeRetry(long backoffMs) {
            retryBackoffs.add(backoffMs);
            timeline.add("adapter:" + backoffMs);
            clock.advance(Duration.ofMillis(backoffMs));
        }

        private List<Long> retryBackoffs() {
            return retryBackoffs;
        }

        private List<String> requestModels() {
            return requestModels;
        }
    }

    private static final class RecordingRetryPolicy extends LlmRetryPolicy {

        private final MutableClock clock;
        private final List<String> timeline;
        private final List<Long> sleepDelays = new ArrayList<>();

        private RecordingRetryPolicy(MutableClock clock, List<String> timeline) {
            this.clock = clock;
            this.timeline = timeline;
        }

        @Override
        public long computeDelay(int attempt, RuntimeConfig.ResilienceConfig config) {
            return 250L;
        }

        @Override
        public void sleep(long delayMs) {
            sleepDelays.add(delayMs);
            timeline.add("L1:" + delayMs);
            clock.advance(Duration.ofMillis(delayMs));
        }

        private List<Long> sleepDelays() {
            return sleepDelays;
        }
    }

    private static final class OneShotRecoveryStrategy implements RecoveryStrategy {

        private int applications;

        @Override
        public String name() {
            return "one_shot";
        }

        @Override
        public boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
            return applications == 0;
        }

        @Override
        public RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
            applications++;
            context.setAttribute("test.l4.degraded", true);
            return RecoveryResult.success("reduced request complexity");
        }

        private int applications() {
            return applications;
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this(new AtomicReference<>(instant), zone);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }
}
