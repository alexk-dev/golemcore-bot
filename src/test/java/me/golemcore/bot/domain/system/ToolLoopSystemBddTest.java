package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BDD Scenario A (contract #1): tool-call -> tool result -> final answer inside
 * ONE turn.
 */
class ToolLoopSystemBddTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void scenarioA_happyPath_toolCallThenToolResultThenFinalAnswer_insideOneTurn() {
        // GIVEN: a session with an incoming user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("chat1")
                .messages(new ArrayList<>())
                .build();

        Message user = Message.builder()
                .role("user")
                .content("Say hello via shell")
                .timestamp(NOW)
                .channelType("telegram")
                .chatId("chat1")
                .build();
        session.addMessage(user);

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        // AND: scripted LLM responses: 1) tool call, 2) final answer
        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();

        LlmResponse first = LlmResponse.builder()
                .content("Let me do that")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("tc1")
                        .name("shell")
                        .arguments(Map.of("command", "echo hello"))
                        .build()))
                .build();

        LlmResponse second = LlmResponse.builder()
                .content("Done: hello")
                .toolCalls(List.of())
                .finishReason("stop")
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            int n = llmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(n == 1 ? first : second);
        });

        // AND: a tool executor (port) returning tool result
        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenReturn(new ToolExecutionOutcome(
                        "tc1",
                        "shell",
                        ToolResult.success("hello\n"),
                        "hello\n",
                        false));

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(llmPort, toolExecutor, historyWriter);

        // WHEN: we process one turn
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN: LLM was called twice inside one turn
        assertEquals(2, llmCalls.get());
        assertEquals(2, result.llmCalls());
        assertTrue(result.finalAnswerReady());

        // AND: raw history (session) contains user -> assistant(toolCalls) -> tool ->
        // assistant(final)
        List<Message> history = session.getMessages();
        assertEquals(4, history.size(), "Expected 4 messages in raw history");

        assertEquals("user", history.get(0).getRole());

        assertEquals("assistant", history.get(1).getRole());
        assertNotNull(history.get(1).getToolCalls());
        assertEquals(1, history.get(1).getToolCalls().size());
        assertEquals("shell", history.get(1).getToolCalls().get(0).getName());
        assertEquals("tc1", history.get(1).getToolCalls().get(0).getId());

        assertEquals("tool", history.get(2).getRole());
        assertEquals("tc1", history.get(2).getToolCallId());
        assertEquals("shell", history.get(2).getToolName());
        assertEquals("hello\n", history.get(2).getContent());

        assertEquals("assistant", history.get(3).getRole());
        assertEquals("Done: hello", history.get(3).getContent());

        // AND: tool was executed once
        verify(toolExecutor, times(1)).execute(any(), any());
    }
}
