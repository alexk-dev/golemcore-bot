package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.domain.voice.VoiceResponseHandler;
import me.golemcore.bot.port.channel.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static me.golemcore.bot.support.ChannelRuntimeTestSupport.responseRoutingSystem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResponseRoutingSystemRuntimeEventsTest {

    private ResponseRoutingSystem system;
    private ChannelPort telegramChannel;

    @BeforeEach
    void setUp() {
        telegramChannel = mock(ChannelPort.class);
        when(telegramChannel.getChannelType()).thenReturn("telegram");
        when(telegramChannel.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        VoiceResponseHandler voiceHandler = mock(VoiceResponseHandler.class);
        system = responseRoutingSystem(List.of(telegramChannel), preferencesService, voiceHandler);
    }

    @Test
    void shouldIgnoreStoredRuntimeEventsAndStillRouteResponse() {
        AgentContext context = contextWithSession("telegram", "chat-1", "transport-1");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("done"));
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of("unexpected-entry"));

        system.process(context);

        verify(telegramChannel).sendMessage(eq("transport-1"), eq("done"), any());
        RoutingOutcome outcome = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertNotNull(outcome);
    }

    @Test
    void shouldContinueRoutingWithoutDispatchingRuntimeEvents() {
        when(telegramChannel.sendMessage(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        AgentContext context = contextWithSession("telegram", "chat-6", null);
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("done"));
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of(Instant.now()));

        assertDoesNotThrow(() -> system.process(context));

        verify(telegramChannel).sendMessage(eq("chat-6"), eq("done"), any());
        RoutingOutcome outcome = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertNotNull(outcome);
        assertTrue(outcome.isSentText());
    }

    @Test
    void shouldHandleMissingSessionWhenRuntimeEventsPresent() {
        AgentContext context = AgentContext.builder()
                .session(null)
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder().build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of("runtime-event-audit-only"));

        assertDoesNotThrow(() -> system.process(context));
    }

    @Test
    void shouldIgnoreRuntimeEventsWhenNoOutgoingResponseIsPresent() {
        AgentContext context = contextWithSession("telegram", "chat-5");
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, "not-a-list");

        assertDoesNotThrow(() -> system.process(context));
        verify(telegramChannel, never()).sendMessage(anyString(), anyString(), any());
    }

    private AgentContext contextWithSession(String channelType, String chatId) {
        return contextWithSession(channelType, chatId, null);
    }

    private AgentContext contextWithSession(String channelType, String chatId, String transportChatId) {
        AgentSession session = AgentSession.builder()
                .id("session-rt")
                .channelType(channelType)
                .chatId(chatId)
                .messages(new ArrayList<>())
                .build();
        if (transportChatId != null) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
            session.setMetadata(metadata);
        }
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }
}
