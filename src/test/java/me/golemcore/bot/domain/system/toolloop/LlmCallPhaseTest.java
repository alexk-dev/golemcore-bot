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
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
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
        org.junit.jupiter.api.Assertions.assertEquals(1, turnState.getEmptyFinalResponseRetries());
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
}
