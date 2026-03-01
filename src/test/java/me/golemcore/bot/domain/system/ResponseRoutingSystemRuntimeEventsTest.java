package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(telegramChannel.sendRuntimeEvent(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        UserPreferencesService preferencesService = mock(UserPreferencesService.class);
        VoiceResponseHandler voiceHandler = mock(VoiceResponseHandler.class);
        system = new ResponseRoutingSystem(List.of(telegramChannel), preferencesService, voiceHandler);
    }

    @Test
    void shouldHandleMixedRuntimeEventsWithoutFailing() {
        AgentContext context = contextWithSession("telegram", "chat-1");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder().build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of(
                "unexpected-entry",
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_STARTED)
                        .timestamp(Instant.now())
                        .sessionId("s1")
                        .channelType("telegram")
                        .chatId("chat-1")
                        .payload(Map.of("phase", "start"))
                        .build()));

        assertDoesNotThrow(() -> system.process(context));
        RoutingOutcome outcome = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertNotNull(outcome);
    }

    @Test
    void shouldSkipRuntimeEventsWhenChannelNotRegistered() {
        AgentContext context = contextWithSession("unknown", "chat-2");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder().build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of(
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FINISHED)
                        .timestamp(Instant.now())
                        .sessionId("s2")
                        .channelType("unknown")
                        .chatId("chat-2")
                        .payload(Map.of())
                        .build()));

        system.process(context);

        verify(telegramChannel, never()).sendRuntimeEvent(anyString(), any());
    }

    @Test
    void shouldHandleMissingSessionWhenRuntimeEventsPresent() {
        AgentContext context = AgentContext.builder()
                .session(null)
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder().build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of(
                RuntimeEvent.builder()
                        .type(RuntimeEventType.TURN_FAILED)
                        .timestamp(Instant.now())
                        .sessionId("s3")
                        .channelType("telegram")
                        .chatId("chat-3")
                        .payload(Map.of("reason", "failure"))
                        .build()));

        assertDoesNotThrow(() -> system.process(context));
    }

    @Test
    void shouldIgnoreNonListRuntimeEventsAttribute() {
        AgentContext context = contextWithSession("telegram", "chat-4");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder().build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, "not-a-list");

        assertDoesNotThrow(() -> system.process(context));
    }

    @Test
    void shouldIgnoreEmptyRuntimeEventsList() {
        AgentContext context = contextWithSession("telegram", "chat-5");
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder().build());
        context.setAttribute(ContextAttributes.RUNTIME_EVENTS, List.of());

        assertDoesNotThrow(() -> system.process(context));
    }

    private AgentContext contextWithSession(String channelType, String chatId) {
        AgentSession session = AgentSession.builder()
                .id("session-rt")
                .channelType(channelType)
                .chatId(chatId)
                .messages(new ArrayList<>())
                .build();
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }
}
