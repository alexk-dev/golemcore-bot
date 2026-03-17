package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InternalTurnServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-17T05:30:00Z");

    @Test
    void shouldPublishInternalAutoContinueMessageWithRuntimeMetadata() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(eventPublisher, clock);

        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, "transport-1");
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, "conversation-1");

        boolean scheduled = service.scheduleAutoContinueRetry(context, "llm-timeout");

        assertTrue(scheduled);
        ArgumentCaptor<AgentLoop.InboundMessageEvent> eventCaptor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        AgentLoop.InboundMessageEvent event = eventCaptor.getValue();
        assertNotNull(event);
        Message message = event.message();
        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals(
                "Continue and finish the previous response. This is an internal auto-continue retry after a model failure. Use the latest visible user request already in the conversation context. Do not ask the user to repeat it unless truly necessary.",
                message.getContent());
        assertEquals("telegram", message.getChannelType());
        assertEquals("chat-1", message.getChatId());
        assertEquals("internal:auto-continue", message.getSenderId());
        assertEquals(FIXED_INSTANT, message.getTimestamp());
        assertTrue(message.isInternalMessage());
        assertEquals(Map.of(
                ContextAttributes.MESSAGE_INTERNAL, true,
                ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE,
                ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY,
                ContextAttributes.TRANSPORT_CHAT_ID, "transport-1",
                ContextAttributes.CONVERSATION_KEY, "conversation-1"), message.getMetadata());
    }

    @Test
    void shouldSkipBlankContextAttributesWhenSchedulingInternalRetry() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(eventPublisher, clock);

        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
        context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, " ");
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, null);

        boolean scheduled = service.scheduleAutoContinueRetry(context, "llm-timeout");

        assertTrue(scheduled);
        ArgumentCaptor<AgentLoop.InboundMessageEvent> eventCaptor = ArgumentCaptor
                .forClass(AgentLoop.InboundMessageEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Message message = eventCaptor.getValue().message();
        assertNotNull(message);
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY,
                message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        assertNull(message.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertNull(message.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldReturnFalseWhenContextOrSessionMissing() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(eventPublisher, clock);

        assertFalse(service.scheduleAutoContinueRetry(null, "llm-timeout"));

        AgentContext contextWithoutSession = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        assertFalse(service.scheduleAutoContinueRetry(contextWithoutSession, "llm-timeout"));

        verifyNoInteractions(eventPublisher);
    }
}
