package me.golemcore.bot.domain.system.toolloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.system.toolloop.view.ConversationViewBuilder;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolExecutionPhaseTest {

    private Clock clock;
    private ToolExecutorPort toolExecutor;
    private ToolFailurePolicy failurePolicy;
    private HistoryWriter historyWriter;
    private LlmCallPhase llmCallPhase;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);
        toolExecutor = mock(ToolExecutorPort.class);
        failurePolicy = mock(ToolFailurePolicy.class);
        historyWriter = mock(HistoryWriter.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenReturn(2_000_000_000);
        llmCallPhase = new LlmCallPhase(
                mock(LlmPort.class),
                mock(ConversationViewBuilder.class),
                modelSelectionService,
                null,
                mock(LlmRequestPreflightPhase.class),
                null,
                null,
                null,
                clock);
    }

    @Test
    void execute_shouldHandlePlanInterceptWithoutCallingToolExecutor() {
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null, clock);
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-1")
                .name("plan_set_content")
                .arguments(Map.of("plan_markdown", "# Plan"))
                .build();
        LlmResponse response = LlmResponse.builder()
                .toolCalls(java.util.List.of(toolCall))
                .build();

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.Continue.class, outcome);
        verify(historyWriter).appendAssistantToolCalls(turnState.getContext(), response, response.getToolCalls());
        verify(historyWriter).appendToolResult(any(), any());
        verifyNoInteractions(toolExecutor);
        verifyNoInteractions(failurePolicy);
        assertTrue(Boolean.TRUE.equals(
                turnState.getContext().getAttribute(ContextAttributes.PLAN_SET_CONTENT_REQUESTED)));
        ToolResult toolResult = turnState.getContext().getToolResults().get("tc-1");
        assertTrue(toolResult.isSuccess());
        assertEquals(1, turnState.getToolExecutions());
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
