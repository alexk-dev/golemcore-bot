package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.model.ToolFailureKind;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.domain.system.toolloop.ToolLoopTurnResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CHAT_1 = "chat1";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String TOOL_CALL_ID_1 = "tc1";
    private static final String TOOL_SHELL = "shell";
    private static final String ARG_COMMAND = "command";
    private static final String TEXT_CALLING_TOOL = "calling tool";
    private static final Instant DEADLINE = Instant.parse("2026-02-01T00:00:00Z");
    private static final int TWO = 2;

    @Test
    void scenarioA_happyPath_toolCallThenToolResultThenFinalAnswer_insideOneTurn() {
        // GIVEN: a session with an incoming user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .messages(new ArrayList<>())
                .build();

        Message user = Message.builder()
                .role(ROLE_USER)
                .content("Say hello via shell")
                .timestamp(NOW)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
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
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "echo hello"))
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
                        TOOL_CALL_ID_1,
                        TOOL_SHELL,
                        ToolResult.success("hello\n"),
                        "hello\n",
                        false,
                        null));

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

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

        assertEquals(ROLE_USER, history.get(0).getRole());

        assertEquals(ROLE_ASSISTANT, history.get(1).getRole());
        assertNotNull(history.get(1).getToolCalls());
        assertEquals(1, history.get(1).getToolCalls().size());
        assertEquals(TOOL_SHELL, history.get(1).getToolCalls().get(0).getName());
        assertEquals(TOOL_CALL_ID_1, history.get(1).getToolCalls().get(0).getId());

        assertEquals(ROLE_TOOL, history.get(2).getRole());
        assertEquals(TOOL_CALL_ID_1, history.get(2).getToolCallId());
        assertEquals(TOOL_SHELL, history.get(2).getToolName());
        assertEquals("hello\n", history.get(2).getContent());

        assertEquals(ROLE_ASSISTANT, history.get(3).getRole());
        assertEquals("Done: hello", history.get(3).getContent());

        // AND: tool was executed once
        verify(toolExecutor, times(1)).execute(any(), any());
    }

    @Test
    void scenarioA2_multiStep_toolCallThenToolResult_repeated_untilFinalAnswer_insideOneTurn() {
        // GIVEN: a session with an incoming user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .messages(new ArrayList<>())
                .build();

        Message user = Message.builder()
                .role(ROLE_USER)
                .content("Do two tool steps")
                .timestamp(NOW)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build();
        session.addMessage(user);

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        // AND: scripted LLM responses: 1) tool call, 2) tool call, 3) final answer
        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();

        LlmResponse r1 = LlmResponse.builder()
                .content("step 1")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "echo one"))
                        .build()))
                .build();

        LlmResponse r2 = LlmResponse.builder()
                .content("step 2")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("tc2")
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "echo two"))
                        .build()))
                .build();

        LlmResponse r3 = LlmResponse.builder()
                .content("final")
                .toolCalls(List.of())
                .finishReason("stop")
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            int n = llmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(n == 1 ? r1 : (n == 2 ? r2 : r3));
        });

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenAnswer(inv -> {
                    Message.ToolCall tc = inv.getArgument(1);
                    String out = TOOL_CALL_ID_1.equals(tc.getId()) ? "one\n" : "two\n";
                    return new ToolExecutionOutcome(
                            tc.getId(),
                            tc.getName(),
                            ToolResult.success(out),
                            out,
                            false,
                            null);
                });

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertEquals(3, llmCalls.get());
        assertEquals(3, result.llmCalls());
        assertTrue(result.finalAnswerReady());

        List<Message> history = session.getMessages();
        // user + (assistant tc1) + (tool tc1) + (assistant tc2) + (tool tc2) +
        // (assistant final)
        assertEquals(6, history.size());

        assertEquals(ROLE_USER, history.get(0).getRole());

        assertEquals(ROLE_ASSISTANT, history.get(1).getRole());
        assertEquals(TOOL_CALL_ID_1, history.get(1).getToolCalls().get(0).getId());
        assertEquals(ROLE_TOOL, history.get(2).getRole());
        assertEquals(TOOL_CALL_ID_1, history.get(2).getToolCallId());

        assertEquals(ROLE_ASSISTANT, history.get(3).getRole());
        assertEquals("tc2", history.get(3).getToolCalls().get(0).getId());
        assertEquals(ROLE_TOOL, history.get(4).getRole());
        assertEquals("tc2", history.get(4).getToolCallId());

        assertEquals(ROLE_ASSISTANT, history.get(5).getRole());
        assertEquals("final", history.get(5).getContent());

        verify(toolExecutor, times(2)).execute(any(), any());
    }

    @Test
    void scenarioStop_maxLlmCalls_shouldProduceFinalAssistantFeedback() {
        // GIVEN: an LLM that always requests the same tool call (never reaches a final
        // answer)
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .messages(new ArrayList<>())
                .build();

        session.addMessage(Message.builder()
                .role(ROLE_USER)
                .content("Loop forever")
                .timestamp(NOW)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCall = LlmResponse.builder()
                .content(TEXT_CALLING_TOOL)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "echo hi"))
                        .build()))
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.completedFuture(toolCall));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenReturn(new ToolExecutionOutcome(
                        TOOL_CALL_ID_1,
                        TOOL_SHELL,
                        ToolResult.success("hi\n"),
                        "hi\n",
                        false,
                        null));

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN: loop ends with a final assistant feedback message
        assertTrue(result.finalAnswerReady());
        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_ASSISTANT.equals(m.getRole())
                && m.getContent() != null
                && m.getContent().contains("Tool loop stopped")));
    }

    @Test
    void shouldStopByDeadlineAndProduceSyntheticToolResults() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build();
        session.addMessage(Message.builder()
                .role(ROLE_USER)
                .content("hi")
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCall = LlmResponse.builder()
                .content(TEXT_CALLING_TOOL)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "echo hi"))
                        .build()))
                .build();
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.completedFuture(toolCall));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);

        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        turn.setMaxLlmCalls(100);
        turn.setMaxToolExecutions(100);
        turn.setDeadline(java.time.Duration.ofMillis(5000));

        Instant start = NOW;
        Clock fastClock = new Clock() {
            int calls = 0;

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                // Call sequence in ToolLoop:
                // 1) compute deadline (start)
                // 2) while condition before first iteration (start)
                // 3) while condition before second iteration (expired)
                calls++;
                if (calls <= TWO) {
                    return start;
                }
                return start.plusSeconds(10);
            }
        };

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();
        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                fastClock);

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        verify(toolExecutor, times(1)).execute(any(), any());
        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_TOOL.equals(m.getRole()) && m.getContent() != null
                && m.getContent().contains("deadline")));
    }

    @Test
    void shouldConvertToolExecutorExceptionToToolFailureAndContinueLoop() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build();
        session.addMessage(Message.builder().role(ROLE_USER).content("hi").build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCall = LlmResponse.builder()
                .content(TEXT_CALLING_TOOL)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "boom"))
                        .build()))
                .build();
        LlmResponse finalAnswer = LlmResponse.builder()
                .content("ok after failure")
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenReturn(
                CompletableFuture.completedFuture(toolCall),
                CompletableFuture.completedFuture(finalAnswer));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenThrow(new RuntimeException("kaboom"));

        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        turn.setMaxLlmCalls(5);
        turn.setMaxToolExecutions(5);
        turn.setDeadline(java.time.Duration.ofMillis(30000));

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();
        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        verify(llmPort, times(2)).chat(any(LlmRequest.class));

        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_TOOL.equals(m.getRole())
                && m.getToolCallId() != null
                && TOOL_CALL_ID_1.equals(m.getToolCallId())
                && m.getContent() != null
                && m.getContent().contains("Tool execution failed")));

        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_ASSISTANT.equals(m.getRole())
                && m.getContent() != null
                && m.getContent().contains("ok after failure")));
    }

    @Test
    void shouldStopImmediatelyWhenStopOnToolFailureIsEnabled() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build();
        session.addMessage(Message.builder().role(ROLE_USER).content("hi").build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCall = LlmResponse.builder()
                .content(TEXT_CALLING_TOOL)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "false"))
                        .build()))
                .build();

        // If we continued the loop, we'd see this call. With stopOnToolFailure we must
        // not.
        LlmResponse wouldBeSecondCall = LlmResponse.builder().content("should not happen").build();

        when(llmPort.chat(any(LlmRequest.class))).thenReturn(
                CompletableFuture.completedFuture(toolCall),
                CompletableFuture.completedFuture(wouldBeSecondCall));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenReturn(new ToolExecutionOutcome(
                        TOOL_CALL_ID_1,
                        TOOL_SHELL,
                        ToolResult.failure("exit 1"),
                        "exit 1",
                        false,
                        null));

        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        turn.setMaxLlmCalls(10);
        turn.setMaxToolExecutions(10);
        turn.setDeadline(java.time.Duration.ofMillis(30000));
        settings.setStopOnToolFailure(true);

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();
        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        verify(llmPort, times(1)).chat(any(LlmRequest.class));
        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_ASSISTANT.equals(m.getRole())
                && m.getContent() != null
                && m.getContent().contains("Tool loop stopped")));
    }

    @Test
    void shouldStopWhenConfirmationDeniedAccordingToPolicy() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build();
        session.addMessage(Message.builder().role(ROLE_USER).content("hi").build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCall = LlmResponse.builder()
                .content(TEXT_CALLING_TOOL)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name(TOOL_SHELL)
                        .arguments(Map.of(ARG_COMMAND, "echo sensitive"))
                        .build()))
                .build();
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.completedFuture(toolCall));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenReturn(new ToolExecutionOutcome(
                        TOOL_CALL_ID_1,
                        TOOL_SHELL,
                        ToolResult.failure(ToolFailureKind.CONFIRMATION_DENIED, "Cancelled by user"),
                        "Error: Cancelled by user",
                        false,
                        null));

        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        turn.setMaxLlmCalls(10);
        turn.setMaxToolExecutions(10);
        turn.setDeadline(java.time.Duration.ofMillis(30000));
        settings.setStopOnConfirmationDenied(true);

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();
        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        verify(llmPort, times(1)).chat(any(LlmRequest.class));
        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_ASSISTANT.equals(m.getRole())
                && m.getContent() != null
                && m.getContent().contains("confirmation denied")));
    }

    @Test
    void shouldStopWhenToolIsDeniedByPolicyAccordingToPolicy() {
        // GIVEN
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build();
        session.addMessage(Message.builder().role(ROLE_USER).content("hi").build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse toolCall = LlmResponse.builder()
                .content(TEXT_CALLING_TOOL)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name("forbidden")
                        .arguments(Map.of())
                        .build()))
                .build();
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(CompletableFuture.completedFuture(toolCall));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenReturn(new ToolExecutionOutcome(
                        TOOL_CALL_ID_1,
                        "forbidden",
                        ToolResult.failure(ToolFailureKind.POLICY_DENIED, "Unknown tool: forbidden"),
                        "Error: Unknown tool: forbidden",
                        false,
                        null));

        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        turn.setMaxLlmCalls(10);
        turn.setMaxToolExecutions(10);
        turn.setDeadline(java.time.Duration.ofMillis(30000));
        settings.setStopOnToolPolicyDenied(true);

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();
        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN
        assertTrue(result.finalAnswerReady());
        verify(llmPort, times(1)).chat(any(LlmRequest.class));
        assertTrue(session.getMessages().stream().anyMatch(m -> ROLE_ASSISTANT.equals(m.getRole())
                && m.getContent() != null
                && m.getContent().contains("tool denied by policy")));
    }

    @Test
    void shouldPropagateToolAttachmentIntoOutgoingResponse() {
        // GIVEN: a session with a user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .messages(new ArrayList<>())
                .build();

        session.addMessage(Message.builder()
                .role(ROLE_USER)
                .content("Take a screenshot")
                .timestamp(NOW)
                .channelType(CHANNEL_TELEGRAM)
                .chatId(CHAT_1)
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .build();

        // AND: an attachment produced by the tool
        Attachment screenshot = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("screenshot.png")
                .mimeType("image/png")
                .build();

        // AND: scripted LLM responses: 1) tool call, 2) final answer
        LlmPort llmPort = mock(LlmPort.class);
        AtomicInteger llmCalls = new AtomicInteger();

        LlmResponse first = LlmResponse.builder()
                .content("Taking screenshot")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id(TOOL_CALL_ID_1)
                        .name("browser")
                        .arguments(Map.of("action", "screenshot"))
                        .build()))
                .build();

        LlmResponse second = LlmResponse.builder()
                .content("Here is the screenshot")
                .toolCalls(List.of())
                .finishReason("stop")
                .build();

        when(llmPort.chat(any(LlmRequest.class))).thenAnswer(inv -> {
            int n = llmCalls.incrementAndGet();
            return CompletableFuture.completedFuture(n == 1 ? first : second);
        });

        // AND: a tool executor returning an outcome with an attachment
        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class)))
                .thenReturn(new ToolExecutionOutcome(
                        TOOL_CALL_ID_1,
                        "browser",
                        ToolResult.success("Screenshot taken"),
                        "Screenshot taken",
                        false,
                        screenshot));

        DefaultHistoryWriter historyWriter = new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC));
        BotProperties.TurnProperties turn = new BotProperties.TurnProperties();
        BotProperties.ToolLoopProperties settings = new BotProperties.ToolLoopProperties();
        BotProperties.ModelRouterProperties router = new BotProperties.ModelRouterProperties();

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                historyWriter,
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                turn,
                settings,
                router,
                null,
                Clock.fixed(DEADLINE, ZoneOffset.UTC));

        // WHEN
        ToolLoopTurnResult result = toolLoop.processTurn(ctx);

        // THEN: loop finished normally
        assertTrue(result.finalAnswerReady());
        assertEquals(2, result.llmCalls());

        // AND: OutgoingResponse on context contains the attachment
        OutgoingResponse outgoing = ctx.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertNotNull(outgoing, "OutgoingResponse must be set on context");
        assertNotNull(outgoing.getAttachments(), "Attachments list must not be null");
        assertEquals(1, outgoing.getAttachments().size());

        Attachment actual = outgoing.getAttachments().get(0);
        assertEquals(Attachment.Type.IMAGE, actual.getType());
        assertEquals("screenshot.png", actual.getFilename());
        assertEquals("image/png", actual.getMimeType());
        assertArrayEquals(new byte[] { 1, 2, 3 }, actual.getData());
    }
}