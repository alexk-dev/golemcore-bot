package me.golemcore.bot.domain.system.toolloop;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmCallPhaseTest {

    private Clock clock;
    private LlmCallPhase phase;
    private HistoryWriter historyWriter;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        org.mockito.Mockito
                .when(modelSelectionService.resolveMaxInputTokensForContext(org.mockito.ArgumentMatchers.any()))
                .thenReturn(2_000_000_000);
        phase = new LlmCallPhase(
                mock(LlmPort.class),
                mock(ConversationViewBuilder.class),
                modelSelectionService,
                null,
                null,
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
