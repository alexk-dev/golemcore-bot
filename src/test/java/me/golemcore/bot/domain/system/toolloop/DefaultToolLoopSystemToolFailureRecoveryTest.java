package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemToolFailureRecoveryTest extends DefaultToolLoopSystemFixture {

    @Test
    void shouldStopOnRepeatedToolFailureWithNormalizedFingerprint() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, TOOL_NAME);
        Message.ToolCall secondCall = toolCall("tc-2", TOOL_NAME);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, " Timeout\n"),
                " Timeout\n", false, null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2", TOOL_NAME,
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "timeout"),
                "timeout", false, null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        assertEquals(2, result.toolExecutions());
        assertFalse(Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TOOL_LOOP_LIMIT_REACHED)));
        assertTrue(context.getFailures().stream()
                .anyMatch(failure -> failure.message().contains("Repeated tool failure: " + TOOL_NAME)));

        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(llmResponse);
        assertTrue(llmResponse.getContent().contains("repeated tool failure (" + TOOL_NAME + ")"));
    }

    @Test
    void shouldInjectRecoveryHintForRepeatedRecoverableShellFailure() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "cat missing.txt"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered after hint")));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2", "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = buildSystemWithRecovery(recoveryService);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        verify(historyWriter).appendInternalRecoveryHint(eq(context), any());
    }

    @Test
    void shouldStopWhenRecoverableShellFailureExhaustsRecoveryBudget() {
        AgentContext context = buildContext();
        List<Message.ToolCall> calls = new ArrayList<>();
        List<ToolExecutionOutcome> failures = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            String callId = index == 1 ? TOOL_CALL_ID : "tc-" + index;
            Message.ToolCall call = toolCall(callId, "shell");
            call.setArguments(Map.of("command", "cat missing.txt"));
            calls.add(call);
            failures.add(new ToolExecutionOutcome(
                    callId, "shell",
                    ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                    "No such file or directory", false, null));
        }
        AtomicInteger llmCallIndex = new AtomicInteger();
        when(llmPort.chat(any())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                toolCallResponse(List.of(calls.get(llmCallIndex.getAndIncrement())))));
        AtomicInteger toolCallIndex = new AtomicInteger();
        when(toolExecutor.execute(any(), any()))
                .thenAnswer(invocation -> failures.get(toolCallIndex.getAndIncrement()));

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = buildSystemWithRecovery(recoveryService);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(8, result.llmCalls());
        verify(historyWriter, times(6)).appendInternalRecoveryHint(eq(context), any());
        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(llmResponse);
        assertTrue(llmResponse.getContent().contains("repeated tool failure (shell)"));
    }

    @Test
    void shouldNotTreatChangedShellCommandAsRepeatedFailure() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "cat missing.txt"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "find . -name missing.txt"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))))
                .thenReturn(CompletableFuture.completedFuture(finalResponse("Recovered")));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "No such file or directory"),
                "No such file or directory", false, null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2", "shell",
                ToolResult.success("./missing.txt"),
                "./missing.txt", false, null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = buildSystemWithRecovery(recoveryService);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(3, result.llmCalls());
        verify(historyWriter, never()).appendInternalRecoveryHint(eq(context), any());
    }

    @Test
    void shouldStopImmediatelyForFatalShellFailure() {
        AgentContext context = buildContext();
        Message.ToolCall firstCall = toolCall(TOOL_CALL_ID, "shell");
        firstCall.setArguments(Map.of("command", "dangerous"));
        Message.ToolCall secondCall = toolCall("tc-2", "shell");
        secondCall.setArguments(Map.of("command", "dangerous"));

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(firstCall))))
                .thenReturn(CompletableFuture.completedFuture(toolCallResponse(List.of(secondCall))));

        ToolExecutionOutcome firstOutcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Command injection detected"),
                "Command injection detected", false, null);
        ToolExecutionOutcome secondOutcome = new ToolExecutionOutcome(
                "tc-2", "shell",
                ToolResult.failure(ToolFailureKind.EXECUTION_FAILED, "Command injection detected"),
                "Command injection detected", false, null);
        when(toolExecutor.execute(any(), any()))
                .thenReturn(firstOutcome)
                .thenReturn(secondOutcome);

        ToolFailureRecoveryService recoveryService = new ToolFailureRecoveryService();
        DefaultToolLoopSystem recoverySystem = buildSystemWithRecovery(recoveryService);

        ToolLoopTurnResult result = recoverySystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        verify(historyWriter, never()).appendInternalRecoveryHint(eq(context), any());
        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        assertNotNull(llmResponse);
        assertTrue(llmResponse.getContent().contains("repeated tool failure (shell)"));
    }
}
