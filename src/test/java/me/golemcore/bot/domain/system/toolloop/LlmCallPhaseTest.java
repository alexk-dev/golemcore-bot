package me.golemcore.bot.domain.system.toolloop;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.FallbackModes;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import me.golemcore.bot.domain.service.TurnProgressService;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.system.LlmErrorClassifier;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.system.toolloop.resilience.LlmResilienceOrchestrator;
import me.golemcore.bot.domain.system.toolloop.resilience.LlmRetryPolicy;
import me.golemcore.bot.domain.system.toolloop.resilience.ProviderCircuitBreaker;
import me.golemcore.bot.domain.system.toolloop.resilience.RecoveryStrategy;
import me.golemcore.bot.domain.system.toolloop.resilience.RuntimeConfigRouterFallbackSelector;
import me.golemcore.bot.domain.system.toolloop.resilience.SuspendedTurnManager;
import me.golemcore.bot.domain.system.toolloop.view.ConversationView;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmCallPhaseTest {

    private Clock clock;
    private LlmCallPhase phase;
    private HistoryWriter historyWriter;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(2_000_000_000);
        phase = new LlmCallPhase(
                mock(LlmPort.class),
                mock(ConversationViewBuilder.class),
                modelSelectionService,
                null,
                mock(LlmRequestPreflightPhase.class),
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                null,
                null,
                clock);
        historyWriter = mock(HistoryWriter.class);
    }

    @Test
    void checkEmptyFinalResponse_shouldScheduleRetryWithinBudget() {
        TurnState turnState = buildTurnState();
        LlmResponse response = LlmResponse.builder()
                .content("")
                .finishReason("stop")
                .build();

        LlmCallPhase.EmptyResponseCheck outcome = phase.checkEmptyFinalResponse(turnState, response, historyWriter);

        assertInstanceOf(LlmCallPhase.EmptyResponseCheck.RetryScheduled.class, outcome);
        assertEquals(1, turnState.getEmptyFinalResponseRetries());
    }

    @Test
    void checkEmptyFinalResponse_shouldFailAfterRetryBudgetIsExhausted() {
        TurnState turnState = buildTurnState();
        turnState.incrementEmptyFinalResponseRetries();
        turnState.incrementEmptyFinalResponseRetries();
        LlmResponse response = LlmResponse.builder()
                .content("")
                .finishReason("stop")
                .build();

        LlmCallPhase.EmptyResponseCheck outcome = phase.checkEmptyFinalResponse(turnState, response, historyWriter);

        LlmCallPhase.EmptyResponseCheck.Failed failed = assertInstanceOf(LlmCallPhase.EmptyResponseCheck.Failed.class,
                outcome);
        assertFalse(failed.result().finalAnswerReady());
        String errorCode = turnState.getContext().getAttribute(ContextAttributes.LLM_ERROR_CODE);
        assertNotNull(errorCode);
        assertTrue(errorCode.startsWith("llm.empty_"));
    }

    @Test
    void execute_shouldFinishLlmSpanWhenPreflightThrows() {
        LlmPort llmPort = mock(LlmPort.class);
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("test-model", null));
        when(modelSelectionService.resolveMaxInputTokensForContext(any())).thenReturn(1_000_000);

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);

        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        RuntimeException preflightBoom = new RuntimeException("preflight boom");
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenThrow(preflightBoom);

        TraceService traceService = mock(TraceService.class);
        TraceContext llmSpan = TraceContext.builder()
                .traceId("trace-1")
                .spanId("span-llm")
                .parentSpanId("span-root")
                .rootKind("USER")
                .build();
        when(traceService.startSpan(any(AgentSession.class), any(TraceContext.class), eq("llm.chat"),
                any(), any(), any()))
                .thenReturn(llmSpan);

        LlmCallPhase tracedPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                runtimeConfigService,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                traceService,
                null,
                clock);

        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
        context.setTraceContext(TraceContext.builder()
                .traceId("trace-1")
                .spanId("span-root")
                .rootKind("USER")
                .build());

        TurnState turnState = new TurnState(
                context,
                null,
                4,
                4,
                clock.instant().plusSeconds(60),
                false,
                true,
                false,
                1,
                10L,
                false);

        LlmCallPhase.LlmCallOutcome outcome = tracedPhase.execute(turnState, historyWriter);

        verify(traceService).finishSpan(
                eq(session),
                eq(llmSpan),
                eq(TraceStatusCode.ERROR),
                any(),
                any());
        assertInstanceOf(LlmCallPhase.LlmCallOutcome.Failed.class, outcome);
    }

    @Test
    void execute_shouldUseForcedRouterFallbackSelection() {
        LlmPort llmPort = mock(LlmPort.class);
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        when(viewBuilder.buildView(any(), eq("provider/fallback"))).thenReturn(ConversationView.ofMessages(List.of()));
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveRouterFallbackSelection(eq(null), eq("provider/fallback"), eq("low")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/fallback", "low"));
        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<LlmRequest>) invocation.getArgument(1)).get());
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("ok")
                        .finishReason("stop")
                        .build()));
        LlmCallPhase fallbackPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                null,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                null,
                null,
                clock);
        TurnState turnState = buildTurnState();
        turnState.getContext().setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL, "provider/fallback");
        turnState.getContext().setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_REASONING, "low");

        LlmCallPhase.LlmCallOutcome outcome = fallbackPhase.execute(turnState, historyWriter);

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.Success.class, outcome);
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("provider/fallback", requestCaptor.getValue().getModel());
        assertEquals("low", requestCaptor.getValue().getReasoningEffort());
        verify(modelSelectionService, never()).resolveForTier(any());
    }

    @Test
    void execute_shouldNormalizeForcedRouterFallbackSelection() {
        LlmPort llmPort = mock(LlmPort.class);
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        when(viewBuilder.buildView(any(), eq("provider/fallback"))).thenReturn(ConversationView.ofMessages(List.of()));
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveRouterFallbackSelection(eq("balanced"), eq("provider/fallback"), eq(null)))
                .thenReturn(new ModelSelectionService.ModelSelection("provider/fallback", "lowest"));
        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<LlmRequest>) invocation.getArgument(1)).get());
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("ok")
                        .finishReason("stop")
                        .build()));
        LlmCallPhase fallbackPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                null,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                null,
                null,
                clock);
        TurnState turnState = buildTurnState();
        turnState.getContext().setModelTier("balanced");
        turnState.getContext().setAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL, "provider/fallback");

        LlmCallPhase.LlmCallOutcome outcome = fallbackPhase.execute(turnState, historyWriter);

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.Success.class, outcome);
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("provider/fallback", requestCaptor.getValue().getModel());
        assertEquals("lowest", requestCaptor.getValue().getReasoningEffort());
        verify(modelSelectionService).resolveRouterFallbackSelection("balanced", "provider/fallback", null);
    }

    @Test
    void execute_shouldFastFailOpenCircuitBeforeCallingPrimaryProvider() {
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("ok")
                        .finishReason("stop")
                        .build()));
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        when(viewBuilder.buildView(any(), any())).thenReturn(ConversationView.ofMessages(List.of()));
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(eq("balanced")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider-primary", null));
        when(modelSelectionService.resolveRouterFallbackSelection(eq("balanced"), eq("provider-fallback"), eq("low")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider-fallback", "low"));
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .coldRetryEnabled(false)
                .build();
        when(runtimeConfigService.isResilienceEnabled()).thenReturn(true);
        when(runtimeConfigService.getResilienceConfig()).thenReturn(config);
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(RuntimeConfig.TierBinding.builder()
                .model("provider-primary")
                .fallbackMode(FallbackModes.SEQUENTIAL)
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder()
                        .model("provider-fallback")
                        .reasoning("low")
                        .build()))
                .build());
        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<LlmRequest>) invocation.getArgument(1)).get());
        ProviderCircuitBreaker circuitBreaker = new ProviderCircuitBreaker(clock, 1, 60, 120);
        circuitBreaker.recordFailure("provider-primary");
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                new ImmediateRetryPolicy(),
                circuitBreaker,
                new RuntimeConfigRouterFallbackSelector(runtimeConfigService),
                List.of(),
                null);
        LlmCallPhase resilientPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                runtimeConfigService,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                null,
                orchestrator,
                clock);
        TurnState turnState = buildResilienceTurnState();

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.RetryScheduled.class,
                resilientPhase.execute(turnState, historyWriter));

        verify(llmPort, never()).chat(any(LlmRequest.class));
        assertEquals("provider-fallback",
                turnState.getContext().getAttribute(ContextAttributes.RESILIENCE_L2_FALLBACK_MODEL));

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.Success.class,
                resilientPhase.execute(turnState, historyWriter));

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(requestCaptor.capture());
        assertEquals("provider-fallback", requestCaptor.getValue().getModel());
    }

    @Test
    void execute_shouldCascadeResilienceThroughLlmCallPhaseUntilSuspended() {
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException(LlmErrorClassifier.withCode(
                        LlmErrorClassifier.LANGCHAIN4J_INTERNAL_SERVER, "provider returned 500"))));
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        when(viewBuilder.buildView(any(), any())).thenReturn(ConversationView.ofMessages(List.of()));
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(eq("balanced")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider-primary", null));
        when(modelSelectionService.resolveRouterFallbackSelection(eq("balanced"), eq("provider-fallback"), eq("low")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider-fallback", "low"));
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(1)
                .hotRetryBaseDelayMs(0L)
                .hotRetryCapMs(1L)
                .coldRetryEnabled(true)
                .coldRetryMaxAttempts(3)
                .build();
        when(runtimeConfigService.isResilienceEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.getResilienceConfig()).thenReturn(config);
        when(runtimeConfigService.getModelTierBinding("balanced")).thenReturn(RuntimeConfig.TierBinding.builder()
                .model("provider-primary")
                .fallbackMode(FallbackModes.SEQUENTIAL)
                .fallbacks(List.of(RuntimeConfig.TierFallback.builder()
                        .model("provider-fallback")
                        .reasoning("low")
                        .build()))
                .build());
        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<LlmRequest>) invocation.getArgument(1)).get());
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);
        TurnProgressService turnProgressService = mock(TurnProgressService.class);
        ProviderCircuitBreaker circuitBreaker = new ProviderCircuitBreaker(clock, 2, 60, 120);
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                new ImmediateRetryPolicy(),
                circuitBreaker,
                new RuntimeConfigRouterFallbackSelector(runtimeConfigService),
                List.of(new OneShotRecoveryStrategy()),
                new SuspendedTurnManager(delayedActionService, clock));
        TraceService traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        LlmCallPhase resilientPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                runtimeConfigService,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                turnProgressService,
                traceService,
                orchestrator,
                clock);
        TurnState turnState = buildResilienceTurnState();
        TraceContext rootTrace = traceService.startRootTrace(turnState.getContext().getSession(),
                "tool-loop.turn", TraceSpanKind.INTERNAL, clock.instant(), Map.of());
        turnState.getContext().setTraceContext(rootTrace);

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.RetryScheduled.class,
                resilientPhase.execute(turnState, historyWriter));
        assertEquals(1, turnState.getRetryAttempt());
        assertEquals("L1", turnState.getContext().getAttribute(ContextAttributes.RESILIENCE_RECOVERY_LAYER));

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.RetryScheduled.class,
                resilientPhase.execute(turnState, historyWriter));
        assertEquals(2, turnState.getRetryAttempt());
        assertEquals("L2", turnState.getContext().getAttribute(ContextAttributes.RESILIENCE_RECOVERY_LAYER));
        assertEquals("provider-fallback", turnState.getContext().getAttribute(ContextAttributes.LLM_MODEL));

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.RetryScheduled.class,
                resilientPhase.execute(turnState, historyWriter));
        assertEquals(3, turnState.getRetryAttempt());
        assertEquals("L4:one_shot", turnState.getContext().getAttribute(ContextAttributes.RESILIENCE_RECOVERY_LAYER));

        LlmCallPhase.LlmCallOutcome.Failed suspended = assertInstanceOf(LlmCallPhase.LlmCallOutcome.Failed.class,
                resilientPhase.execute(turnState, historyWriter));
        assertFalse(suspended.result().finalAnswerReady());
        assertTrue(
                Boolean.TRUE.equals(turnState.getContext().getAttribute(ContextAttributes.RESILIENCE_TURN_SUSPENDED)));
        verify(delayedActionService).schedule(any(DelayedSessionAction.class));

        ArgumentCaptor<LlmRequest> requests = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort, org.mockito.Mockito.times(4)).chat(requests.capture());
        assertEquals(List.of("provider-primary", "provider-primary", "provider-fallback", "provider-fallback"),
                requests.getAllValues().stream().map(LlmRequest::getModel).toList());

        List<TraceSpanRecord> spans = turnState.getContext().getSession().getTraces().getFirst().getSpans();
        assertNotNull(findSpan(spans, "llm.resilience.L1"));
        TraceSpanRecord l2Span = findSpan(spans, "llm.resilience.L2");
        assertNotNull(l2Span);
        assertEquals("retry_now", l2Span.getAttributes().get("resilience.action"));
        assertEquals("provider-primary", l2Span.getAttributes().get("model.before"));
        assertEquals("provider-fallback", l2Span.getAttributes().get("model.after"));
        assertEquals(Boolean.TRUE, l2Span.getAttributes().get("model.changed"));
        assertEquals(FallbackModes.SEQUENTIAL, l2Span.getAttributes().get("fallback.mode"));

        TraceSpanRecord l3Span = findSpan(spans, "llm.resilience.L3");
        assertNotNull(l3Span);
        assertEquals("state_transition", l3Span.getAttributes().get("resilience.action"));
        assertEquals("OPEN", l3Span.getAttributes().get("circuit.state.after"));
        assertEquals(Boolean.FALSE, l3Span.getAttributes().get("model.changed"));

        TraceSpanRecord l4Span = findSpan(spans, "llm.resilience.L4");
        assertNotNull(l4Span);
        assertEquals("one_shot", l4Span.getAttributes().get("resilience.strategy"));

        TraceSpanRecord l5Span = findSpan(spans, "llm.resilience.L5");
        assertNotNull(l5Span);
        assertEquals("suspend", l5Span.getAttributes().get("resilience.action"));

        TraceSpanRecord modelSwitchSpan = findSpan(spans, "llm.model.switch");
        assertNotNull(modelSwitchSpan);
        assertEquals("L2", modelSwitchSpan.getAttributes().get("resilience.layer"));
        assertEquals("provider-primary", modelSwitchSpan.getAttributes().get("model.before"));
        assertEquals("provider-fallback", modelSwitchSpan.getAttributes().get("model.after"));

        ArgumentCaptor<String> progressText = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> progressMetadata = ArgumentCaptor.forClass(Map.class);
        verify(turnProgressService, org.mockito.Mockito.times(6)).publishSummary(eq(turnState.getContext()),
                progressText.capture(), progressMetadata.capture());
        List<String> notices = progressText.getAllValues();
        assertTrue(notices.stream().anyMatch(text -> text.contains("Retrying the same LLM model")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Switching to fallback model provider-fallback")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Circuit breaker moved CLOSED -> OPEN")));
        assertTrue(notices.stream().anyMatch(text -> text.contains("Applying LLM degradation: one_shot")));
        assertTrue(notices.stream().anyMatch(text -> text.startsWith("Turn suspended:")
                && text.contains("retry automatically in 2 minute(s)")));
        assertTrue(progressMetadata.getAllValues().stream()
                .anyMatch(metadata -> "L2".equals(metadata.get("resilience.layer"))
                        && "provider-fallback".equals(metadata.get("fallback.model"))));
        OutgoingResponse suspensionResponse = turnState.getContext().getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(suspensionResponse);
        assertTrue(suspensionResponse.getText().contains("retry automatically in 2 minute(s)"));
    }

    @Test
    void execute_shouldSkipProgressNoticeForUnknownResilienceLayer() {
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException(LlmErrorClassifier.withCode(
                        LlmErrorClassifier.UNKNOWN, "provider failed"))));
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        when(viewBuilder.buildView(any(), any())).thenReturn(ConversationView.ofMessages(List.of()));
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(eq("balanced")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider-primary", null));
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();
        when(runtimeConfigService.isResilienceEnabled()).thenReturn(true);
        when(runtimeConfigService.getResilienceConfig()).thenReturn(config);
        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<LlmRequest>) invocation.getArgument(1)).get());
        TurnProgressService turnProgressService = mock(TurnProgressService.class);
        LlmResilienceOrchestrator orchestrator = mock(LlmResilienceOrchestrator.class);
        LlmResilienceOrchestrator.ResilienceTraceStep unknownStep = new LlmResilienceOrchestrator.ResilienceTraceStep(
                "L6", "novel_action",
                "brand-new recovery layer", Map.of());
        when(orchestrator.handle(any(AgentContext.class), any(), any(), anyInt(), any()))
                .thenReturn(new LlmResilienceOrchestrator.ResilienceOutcome.Exhausted(
                        "exhausted", List.of(unknownStep)));
        LlmCallPhase resilientPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                runtimeConfigService,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                turnProgressService,
                null,
                orchestrator,
                clock);
        TurnState turnState = buildResilienceTurnState();

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.Failed.class,
                resilientPhase.execute(turnState, historyWriter));

        verify(turnProgressService, never()).publishSummary(any(AgentContext.class), any(String.class), any());
    }

    @Test
    void execute_shouldOnlyMarkTerminalResilienceSpanAsErrorWhenCascadeExhausts() {
        LlmPort llmPort = mock(LlmPort.class);
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException(LlmErrorClassifier.withCode(
                        LlmErrorClassifier.UNKNOWN, "provider failed"))));
        ConversationViewBuilder viewBuilder = mock(ConversationViewBuilder.class);
        when(viewBuilder.buildView(any(), any())).thenReturn(ConversationView.ofMessages(List.of()));
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(eq("balanced")))
                .thenReturn(new ModelSelectionService.ModelSelection("provider-primary", null));
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .hotRetryMaxAttempts(0)
                .coldRetryEnabled(false)
                .build();
        when(runtimeConfigService.isResilienceEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigService.getResilienceConfig()).thenReturn(config);
        LlmRequestPreflightPhase preflightPhase = mock(LlmRequestPreflightPhase.class);
        when(preflightPhase.preflight(any(AgentContext.class), any(), anyInt()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<LlmRequest>) invocation.getArgument(1)).get());
        ProviderCircuitBreaker circuitBreaker = new ProviderCircuitBreaker(clock, 1, 60, 120);
        LlmResilienceOrchestrator orchestrator = new LlmResilienceOrchestrator(
                new ImmediateRetryPolicy(), circuitBreaker, List.of(), null);
        TraceService traceService = new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
        LlmCallPhase resilientPhase = new LlmCallPhase(
                llmPort,
                viewBuilder,
                modelSelectionService,
                runtimeConfigService,
                preflightPhase,
                mock(ContextCompactionCoordinator.class),
                null,
                null,
                traceService,
                orchestrator,
                clock);
        TurnState turnState = buildResilienceTurnState();
        TraceContext rootTrace = traceService.startRootTrace(turnState.getContext().getSession(),
                "tool-loop.turn", TraceSpanKind.INTERNAL, clock.instant(), Map.of());
        turnState.getContext().setTraceContext(rootTrace);

        assertInstanceOf(LlmCallPhase.LlmCallOutcome.Failed.class,
                resilientPhase.execute(turnState, historyWriter));

        List<TraceSpanRecord> spans = turnState.getContext().getSession().getTraces().getFirst().getSpans();
        TraceSpanRecord l3Span = findSpan(spans, "llm.resilience.L3");
        assertNotNull(l3Span);
        assertEquals(TraceStatusCode.OK, l3Span.getStatusCode());
        TraceSpanRecord l5Span = findSpan(spans, "llm.resilience.L5");
        assertNotNull(l5Span);
        assertEquals("exhausted", l5Span.getAttributes().get("resilience.action"));
        assertEquals(TraceStatusCode.ERROR, l5Span.getStatusCode());
    }

    @Test
    void finalizeFinalAnswer_shouldAppendHistoryAndMarkTurnFinished() {
        TurnState turnState = buildTurnState();
        LlmResponse response = LlmResponse.builder()
                .content("Done")
                .finishReason("stop")
                .build();

        ToolLoopTurnResult result = phase.finalizeFinalAnswer(turnState, response, historyWriter);

        verify(historyWriter).appendFinalAssistantAnswer(turnState.getContext(), response, "Done");
        assertTrue(result.finalAnswerReady());
        assertTrue(Boolean.TRUE.equals(turnState.getContext().getAttribute(ContextAttributes.FINAL_ANSWER_READY)));
    }

    private TurnState buildTurnState() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .currentIteration(0)
                .build();
        return new TurnState(
                context,
                null,
                4,
                4,
                clock.instant().plusSeconds(60),
                false,
                true,
                false,
                1,
                10L,
                true);
    }

    private TurnState buildResilienceTurnState() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(List.of(Message.builder()
                        .role("user")
                        .content("run cascade")
                        .timestamp(clock.instant())
                        .build()))
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(session.getMessages())
                .modelTier("balanced")
                .maxIterations(1)
                .currentIteration(0)
                .build();
        return new TurnState(
                context,
                null,
                8,
                4,
                clock.instant().plusSeconds(60),
                false,
                true,
                false,
                1,
                0L,
                true);
    }

    private TraceSpanRecord findSpan(List<TraceSpanRecord> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElse(null);
    }

    private static final class OneShotRecoveryStrategy implements RecoveryStrategy {
        private boolean applied;

        @Override
        public String name() {
            return "one_shot";
        }

        @Override
        public boolean isApplicable(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
            return !applied;
        }

        @Override
        public RecoveryResult apply(AgentContext context, String errorCode, RuntimeConfig.ResilienceConfig config) {
            applied = true;
            return RecoveryResult.success("reduced request complexity");
        }
    }

    private static final class ImmediateRetryPolicy extends LlmRetryPolicy {
        @Override
        public long computeDelay(int attempt, RuntimeConfig.ResilienceConfig config) {
            return 0L;
        }

        @Override
        public void sleep(long delayMs) {
            // Keep the LlmCallPhase cascade test deterministic and fast.
        }
    }
}
