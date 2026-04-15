package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolLoopSystemTest extends DefaultToolLoopSystemFixture {

    @Test
    void shouldReturnFinalAnswerWhenLlmReturnsNoToolCalls() {
        AgentContext context = buildContext();
        LlmResponse response = finalResponse("Hello!");

        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(1, result.llmCalls());
        assertEquals(0, result.toolExecutions());
        verify(historyWriter).appendFinalAssistantAnswer(any(), any(), any());
    }

    @Test
    void shouldExecuteToolCallsAndContinue() {
        AgentContext context = buildContext();
        Message.ToolCall tc = toolCall(TOOL_CALL_ID, TOOL_NAME);

        LlmResponse withTools = toolCallResponse(List.of(tc));
        LlmResponse finalResp = finalResponse(CONTENT_DONE);

        when(llmPort.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(withTools))
                .thenReturn(CompletableFuture.completedFuture(finalResp));

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TOOL_CALL_ID, TOOL_NAME, ToolResult.success("ok"), "ok", false, null);
        when(toolExecutor.execute(any(), any())).thenReturn(outcome);

        ToolLoopTurnResult result = system.processTurn(context);

        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());
        assertEquals(1, result.toolExecutions());
    }

    @Test
    void builder_shouldFailFastWhenContextCompactionPolicyMissing() {
        // Locks the explicit-wiring invariant: a future refactor that
        // reintroduces a silent fallback budget policy must fail this test
        // instead of quietly masking a broken
        // bean graph at runtime.
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .modelSelectionService(modelSelectionService)
                .clock(clock)
                .build());

        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("contextCompactionPolicy"),
                "NPE message must name the missing collaborator so operators can fix wiring quickly");
    }

    @Test
    void shouldUseDefaultsWhenSettingsAreNull() {
        DefaultToolLoopSystem nullSettingsSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .modelSelectionService(modelSelectionService)
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        AgentContext context = buildContext();
        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = nullSettingsSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }

    @Test
    void shouldUseNullModelWhenModelSelectionServiceIsNull() {
        DefaultToolLoopSystem nullRouterSystem = DefaultToolLoopSystem.builder()
                .llmPort(llmPort)
                .toolExecutor(toolExecutor)
                .historyWriter(historyWriter)
                .viewBuilder(viewBuilder)
                .settings(me.golemcore.bot.support.TestPorts.toolLoop(settings))
                .contextCompactionPolicy(new ContextCompactionPolicy(runtimeConfigService, modelSelectionService))
                .clock(clock)
                .build();

        AgentContext context = buildContext();
        LlmResponse response = finalResponse(CONTENT_HELLO);
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(response));

        ToolLoopTurnResult result = nullRouterSystem.processTurn(context);

        assertTrue(result.finalAnswerReady());
    }
}
