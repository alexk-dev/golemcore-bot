package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.system.toolloop.DefaultHistoryWriter;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.system.toolloop.ToolExecutionOutcome;
import me.golemcore.bot.domain.system.toolloop.ToolExecutorPort;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BDD: ToolLoop writes raw history (assistant final answer).
 * ResponseRoutingSystem must not duplicate it.
 */
class ToolLoopNoDuplicateHistoryBddTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void toolLoopPersistsFinalAssistant_thenResponseRoutingSendsButDoesNotMutateHistory() {
        // GIVEN: a session + context with one user message
        AgentSession session = AgentSession.builder()
                .id("s1")
                .channelType("telegram")
                .chatId("chat1")
                .metadata(new java.util.HashMap<>())
                .messages(new ArrayList<>())
                .build();

        session.addMessage(Message.builder()
                .role("user")
                .content("Say hello via shell")
                .timestamp(NOW)
                .channelType("telegram")
                .chatId("chat1")
                .build());

        AgentContext ctx = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .availableTools(List.of())
                .build();

        // AND: scripted LLM responses: tool call -> final
        LlmPort llmPort = mock(LlmPort.class);
        LlmResponse first = LlmResponse.builder()
                .content("Calling tool")
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
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(
                CompletableFuture.completedFuture(first),
                CompletableFuture.completedFuture(second));

        ToolExecutorPort toolExecutor = mock(ToolExecutorPort.class);
        when(toolExecutor.execute(any(AgentContext.class), any(Message.ToolCall.class))).thenReturn(
                new ToolExecutionOutcome("tc1", "shell", ToolResult.success("hello\n"), "hello\n", false, null));

        DefaultToolLoopSystem toolLoop = new DefaultToolLoopSystem(
                llmPort,
                toolExecutor,
                new DefaultHistoryWriter(Clock.fixed(NOW, ZoneOffset.UTC)),
                new me.golemcore.bot.domain.system.toolloop.view.DefaultConversationViewBuilder(
                        new me.golemcore.bot.domain.system.toolloop.view.FlatteningToolMessageMasker()),
                new BotProperties.TurnProperties(),
                new BotProperties.ToolLoopProperties(),
                new BotProperties.ModelRouterProperties(),
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        // WHEN: ToolLoop runs
        toolLoop.processTurn(ctx);

        // THEN: ToolLoop persisted final assistant in raw history
        int historySizeAfterToolLoop = session.getMessages().size();
        assertEquals(4, historySizeAfterToolLoop);
        assertEquals("assistant", session.getMessages().get(3).getRole());
        assertEquals("Done: hello", session.getMessages().get(3).getContent());

        // AND WHEN: ResponseRouting runs (transport-only)
        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn("telegram");
        when(channel.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        VoiceResponseHandler voiceHandler = mock(VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        ResponseRoutingSystem routing = new ResponseRoutingSystem(List.of(channel), preferences, voiceHandler);

        ctx.setAttribute(ContextAttributes.OUTGOING_RESPONSE,
                me.golemcore.bot.domain.model.OutgoingResponse.textOnly(second.getContent()));

        routing.process(ctx);

        // THEN: message is sent to channel
        verify(channel).sendMessage(eq("chat1"), eq("Done: hello"));

        // AND: raw history is not mutated by routing
        assertEquals(historySizeAfterToolLoop, session.getMessages().size());

        // AND: routing does not attempt voice
        verify(voiceHandler, never()).trySendVoice(any(), anyString(), anyString());
    }
}
