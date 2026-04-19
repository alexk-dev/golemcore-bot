package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemStopConditionTest extends DefaultToolLoopSystemFixture {

    @Test
    void shouldStopOnConfirmationDenied() {
        settings.setStopOnConfirmationDenied(true);
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, USER_DENIED),
                USER_DENIED, false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
    }

    @Test
    void shouldStopOnToolPolicyDenied() {
        settings.setStopOnToolPolicyDenied(true);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Policy denied"),
                "Policy denied", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldStopOnToolFailureWhenEnabled() {
        settings.setStopOnToolFailure(true);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Failed"),
                "Failed", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldStopOnToolFailureWhenOutcomeToolNameIsMissing() {
        settings.setStopOnToolFailure(true);
        DefaultToolLoopSystem runtimeEventSystem = buildSystemWithRuntimeEvents();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, null,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Failed"),
                "Failed", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = assertDoesNotThrow(() -> runtimeEventSystem.processTurn(context));

        assertTrue(result.finalAnswerReady());
        List<RuntimeEvent> events = context.getAttribute(ContextAttributes.RUNTIME_EVENTS);
        assertNotNull(events);

        RuntimeEvent turnFinished = events.stream()
                .filter(event -> event.type() == RuntimeEventType.TURN_FINISHED)
                .filter(event -> "tool_failure".equals(event.payload().get("reason")))
                .findFirst()
                .orElseThrow();
        assertNull(turnFinished.payload().get("tool"));
    }

    @Test
    void shouldNotStopOnConfirmationDeniedWhenDisabled() {
        settings.setStopOnConfirmationDenied(false);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse("Continued anyway");

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "Denied"),
                "Denied", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertEquals(2, result.llmCalls());
    }

    @Test
    void shouldStopWhenMaxLlmCallsReached() {
        turnSettings.setMaxLlmCalls(2);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
    }

    @Test
    void shouldStopWhenMaxToolExecutionsReached() {
        turnSettings.setMaxToolExecutions(1);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc1 = toolCall(TOOL_CALL_ID, TOOL_NAME);
        Message.ToolCall tc2 = toolCall("tc-2", TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc1, tc2));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldSkipDuplicateSyntheticResultsInStopTurn() {
        turnSettings.setMaxLlmCalls(1);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc1 = toolCall(TOOL_CALL_ID, TOOL_NAME);
        Message.ToolCall tc2 = toolCall("tc-2", TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc1, tc2));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome1 = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        ToolExecutionOutcome outcome2 = new ToolExecutionOutcome(
                "tc-2", TOOL_NAME, ToolResult.success("ok2"), "ok2", false, null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(outcome1)
                .thenReturn(outcome2);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldReplaceLlmResponseWithCleanResponseOnStop() {
        turnSettings.setMaxLlmCalls(1);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());

        LlmResponse replacedResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(replacedResponse);
        assertFalse(replacedResponse.hasToolCalls(), "LLM_RESPONSE should have no tool calls after stop");
        assertTrue(replacedResponse.getContent().contains("reached max internal LLM calls"),
                "LLM_RESPONSE content should contain the stop reason");
    }

    @Test
    void shouldSetToolLoopLimitReachedWhenMaxLlmCallsExhausted() {
        turnSettings.setMaxLlmCalls(1);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        Boolean limitReached = context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED);
        assertTrue(Boolean.TRUE.equals(limitReached),
                "TOOL_LOOP_LIMIT_REACHED should be set when LLM call limit exhausted");
    }

    @Test
    void shouldSetLimitReasonWhenMaxLlmCallsExhausted() {
        turnSettings.setMaxLlmCalls(1);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        assertEquals(me.golemcore.bot.domain.model.TurnLimitReason.MAX_LLM_CALLS,
                context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON));
    }

    @Test
    void shouldSetLimitReasonWhenMaxToolExecutionsExhausted() {
        turnSettings.setMaxToolExecutions(1);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        assertEquals(me.golemcore.bot.domain.model.TurnLimitReason.MAX_TOOL_EXECUTIONS,
                context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REASON));
    }

    @Test
    void shouldNotSetToolLoopLimitReachedOnConfirmationDenied() {
        settings.setStopOnConfirmationDenied(true);
        system = buildSystem();
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(withTools));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, USER_DENIED),
                USER_DENIED, false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        system.processTurn(context);

        Boolean limitReached = context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED);
        assertFalse(Boolean.TRUE.equals(limitReached),
                "TOOL_LOOP_LIMIT_REACHED should NOT be set for confirmation denied stop");
    }

    @Test
    void shouldStopImmediatelyWhenInterruptFlagSetBeforeLlmCall() {
        AgentContext context = buildContext();
        context.getSession().setMetadata(new HashMap<>());
        context.getSession().getMetadata().put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);

        ToolLoopTurnResult result = system.processTurn(context);

        assertNotNull(result);
        assertTrue(result.finalAnswerReady());
        verify(llmPort, never()).chat(any());
    }
}
