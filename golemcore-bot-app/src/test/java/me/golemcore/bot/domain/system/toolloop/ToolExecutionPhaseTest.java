package me.golemcore.bot.domain.system.toolloop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.service.PlanService;
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
    private PlanService planService;
    private PlanModeToolRestrictionService planModeToolRestrictionService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC);
        toolExecutor = mock(ToolExecutorPort.class);
        failurePolicy = mock(ToolFailurePolicy.class);
        historyWriter = mock(HistoryWriter.class);
        planService = new PlanService(clock, null);
        planModeToolRestrictionService = new PlanModeToolRestrictionService(planService);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveMaxInputTokensForContext(any()))
                .thenReturn(2_000_000_000);
        llmCallPhase = new LlmCallPhase(
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
    }

    @Test
    void execute_shouldDenyShellToolInPlanModeWithoutExecutingIt() {
        planService.activatePlanMode(new SessionIdentity("web", "chat-1"), "chat-1", null);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null,
                clock, planModeToolRestrictionService);
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-shell")
                .name(ToolNames.SHELL)
                .arguments(Map.of("command", "pwd"))
                .build();
        LlmResponse response = LlmResponse.builder()
                .toolCalls(java.util.List.of(toolCall))
                .build();
        when(failurePolicy.evaluate(any(), any(), any()))
                .thenReturn(new ToolFailurePolicy.Verdict.Ok());

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.Continue.class, outcome);
        verify(toolExecutor, never()).execute(any(), any());
        ToolResult toolResult = turnState.getContext().getToolResults().get("tc-shell");
        assertFalse(toolResult.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Plan mode is active"));
    }

    @Test
    void execute_shouldAllowGoalManagementToolInPlanMode() {
        planService.activatePlanMode(new SessionIdentity("web", "chat-1"), "chat-1", null);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null,
                clock, planModeToolRestrictionService);
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-goal")
                .name(ToolNames.GOAL_MANAGEMENT)
                .arguments(Map.of("operation", "create_goal", "title", "Investigate"))
                .build();
        LlmResponse response = LlmResponse.builder()
                .toolCalls(java.util.List.of(toolCall))
                .build();
        ToolExecutionOutcome toolOutcome = new ToolExecutionOutcome(
                "tc-goal", ToolNames.GOAL_MANAGEMENT, ToolResult.success("created"), "created", false, null);
        when(toolExecutor.execute(turnState.getContext(), toolCall)).thenReturn(toolOutcome);

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.Continue.class, outcome);
        verify(toolExecutor).execute(turnState.getContext(), toolCall);
        assertTrue(turnState.getContext().getToolResults().get("tc-goal").isSuccess());
    }

    @Test
    void execute_shouldDenyRemovedPlanSetContentToolInPlanMode() {
        planService.activatePlanMode(new SessionIdentity("web", "chat-1"), "chat-1", null);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null,
                clock, planModeToolRestrictionService);
        TurnState turnState = buildTurnState();
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("tc-1")
                .name("plan_set_content")
                .arguments(Map.of("plan_markdown", "# Plan"))
                .build();
        LlmResponse response = LlmResponse.builder()
                .toolCalls(java.util.List.of(toolCall))
                .build();
        when(failurePolicy.evaluate(any(), any(), any()))
                .thenReturn(new ToolFailurePolicy.Verdict.Ok());

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.Continue.class, outcome);
        verify(toolExecutor, never()).execute(turnState.getContext(), toolCall);
        ToolResult toolResult = turnState.getContext().getToolResults().get("tc-1");
        assertFalse(toolResult.isSuccess());
        assertEquals(ToolFailureKind.POLICY_DENIED, toolResult.getFailureKind());
        assertTrue(toolResult.getError().contains("Plan mode is active"));
        assertEquals(1, turnState.getToolExecutions());
    }

    @Test
    void execute_shouldStopBatchAfterPlanExitInPlanMode() {
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        planService.activatePlanMode(sessionIdentity, "chat-1", null);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null,
                clock, planModeToolRestrictionService);
        TurnState turnState = buildTurnState();
        String planText = """
                # Plan
                1. Inspect the request.
                2. Implement the change after the user approves.
                """.trim();
        Message.ToolCall planExitCall = Message.ToolCall.builder()
                .id("tc-plan-exit")
                .name(ToolNames.PLAN_EXIT)
                .arguments(Map.of())
                .build();
        Message.ToolCall shellCall = Message.ToolCall.builder()
                .id("tc-shell")
                .name(ToolNames.SHELL)
                .arguments(Map.of("command", "pwd"))
                .build();
        LlmResponse response = LlmResponse.builder()
                .content(planText)
                .toolCalls(java.util.List.of(planExitCall, shellCall))
                .build();
        turnState.getContext().setAttribute(ContextAttributes.LLM_RESPONSE, response);
        ToolExecutionOutcome planExitOutcome = new ToolExecutionOutcome(
                "tc-plan-exit", ToolNames.PLAN_EXIT, ToolResult.success("done"), "done", false, null);
        when(toolExecutor.execute(turnState.getContext(), planExitCall)).thenAnswer(invocation -> {
            planService.completePlanMode(sessionIdentity);
            return planExitOutcome;
        });

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.StopTurn.class, outcome);
        verify(toolExecutor).execute(turnState.getContext(), planExitCall);
        verify(toolExecutor, never()).execute(turnState.getContext(), shellCall);
        ToolResult skippedShell = turnState.getContext().getToolResults().get("tc-shell");
        assertFalse(skippedShell.isSuccess());
        assertTrue(skippedShell.getError().contains("plan mode completed"));
        LlmResponse finalResponse = turnState.getContext().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertEquals(planText, finalResponse.getContent());
        assertTrue(Boolean.TRUE.equals(turnState.getContext().getAttribute(ContextAttributes.FINAL_ANSWER_READY)));
        assertFalse(planService.isPlanModeActive(sessionIdentity));
        verify(historyWriter).appendFinalAssistantAnswer(eq(turnState.getContext()), eq(response), eq(planText));
    }

    @Test
    void execute_shouldUsePlanExitPlanMarkdownWhenAssistantContentIsBlank() {
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        planService.activatePlanMode(sessionIdentity, "chat-1", null);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null,
                clock, planModeToolRestrictionService);
        TurnState turnState = buildTurnState();
        String planText = """
                # Plan
                1. Inspect the code path.
                2. Wait for approval before changing files.
                """.trim();
        Message.ToolCall planExitCall = Message.ToolCall.builder()
                .id("tc-plan-exit")
                .name(ToolNames.PLAN_EXIT)
                .arguments(Map.of("plan_markdown", planText))
                .build();
        LlmResponse response = LlmResponse.builder()
                .content(" ")
                .toolCalls(java.util.List.of(planExitCall))
                .build();
        turnState.getContext().setAttribute(ContextAttributes.LLM_RESPONSE, response);
        ToolExecutionOutcome planExitOutcome = new ToolExecutionOutcome(
                "tc-plan-exit", ToolNames.PLAN_EXIT, ToolResult.success("done"), "done", false, null);
        when(toolExecutor.execute(turnState.getContext(), planExitCall)).thenAnswer(invocation -> {
            planService.completePlanMode(sessionIdentity);
            return planExitOutcome;
        });

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.StopTurn.class, outcome);
        LlmResponse finalResponse = turnState.getContext().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertEquals(planText, finalResponse.getContent());
        verify(historyWriter).appendFinalAssistantAnswer(eq(turnState.getContext()), eq(response), eq(planText));
    }

    @Test
    void execute_shouldPreferPlanExitPlanMarkdownOverAssistantContent() {
        SessionIdentity sessionIdentity = new SessionIdentity("web", "chat-1");
        planService.activatePlanMode(sessionIdentity, "chat-1", null);
        ToolExecutionPhase phase = new ToolExecutionPhase(toolExecutor, failurePolicy, null, null, null, null,
                clock, planModeToolRestrictionService);
        TurnState turnState = buildTurnState();
        String planText = """
                # Plan
                1. Inspect the implementation.
                2. Ask for approval before making changes.
                """.trim();
        Message.ToolCall planExitCall = Message.ToolCall.builder()
                .id("tc-plan-exit")
                .name(ToolNames.PLAN_EXIT)
                .arguments(Map.of("plan_markdown", planText))
                .build();
        LlmResponse response = LlmResponse.builder()
                .content("The plan is done.")
                .toolCalls(java.util.List.of(planExitCall))
                .build();
        turnState.getContext().setAttribute(ContextAttributes.LLM_RESPONSE, response);
        ToolExecutionOutcome planExitOutcome = new ToolExecutionOutcome(
                "tc-plan-exit", ToolNames.PLAN_EXIT, ToolResult.success("done"), "done", false, null);
        when(toolExecutor.execute(turnState.getContext(), planExitCall)).thenAnswer(invocation -> {
            planService.completePlanMode(sessionIdentity);
            return planExitOutcome;
        });

        ToolExecutionPhase.ToolBatchOutcome outcome = phase.execute(turnState, response, historyWriter, llmCallPhase);

        assertInstanceOf(ToolExecutionPhase.ToolBatchOutcome.StopTurn.class, outcome);
        LlmResponse finalResponse = turnState.getContext().getAttribute(ContextAttributes.LLM_RESPONSE);
        assertEquals(planText, finalResponse.getContent());
        verify(historyWriter).appendFinalAssistantAnswer(eq(turnState.getContext()), eq(response), eq(planText));
    }

    private TurnState buildTurnState() {
        AgentSession session = AgentSession.builder()
                .id("sess-1")
                .channelType("web")
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
