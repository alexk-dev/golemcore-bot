package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        Message message = messageCaptor.getValue();
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
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY,
                message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        assertEquals("transport-1", message.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conversation-1", message.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
        assertEquals("INTERNAL", message.getMetadata().get("trace.root.kind"));
        assertEquals("internal.auto_continue", message.getMetadata().get("trace.name"));
        assertNotNull(message.getMetadata().get("trace.id"));
        assertNotNull(message.getMetadata().get("trace.span.id"));
    }

    @Test
    void shouldSkipBlankContextAttributesWhenSchedulingInternalRetry() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        Message message = messageCaptor.getValue();
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
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        assertFalse(service.scheduleAutoContinueRetry(null, "llm-timeout"));

        AgentContext contextWithoutSession = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        assertFalse(service.scheduleAutoContinueRetry(contextWithoutSession, "llm-timeout"));

        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldPublishFollowThroughNudgeWithContinuationPromptAsUserMessage() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        boolean scheduled = service.scheduleFollowThroughNudge(
                context, "Gather the three files you committed to.", 0);

        assertTrue(scheduled);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals("Gather the three files you committed to.", message.getContent());
        assertEquals("telegram", message.getChannelType());
        assertEquals("chat-1", message.getChatId());
        assertEquals("internal:follow-through", message.getSenderId());
        assertEquals(FIXED_INSTANT, message.getTimestamp());
        assertTrue(message.isInternalMessage());
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY,
                message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        assertEquals(1, message.getMetadata().get(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH));
        assertEquals("transport-1", message.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conversation-1", message.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
        assertEquals("INTERNAL", message.getMetadata().get("trace.root.kind"));
        assertEquals("resilience.follow_through.nudge", message.getMetadata().get("trace.name"));
        assertNotNull(message.getMetadata().get("trace.id"));
        assertNotNull(message.getMetadata().get("trace.span.id"));
    }

    @Test
    void shouldIncrementChainDepthForFollowThroughNudgeRelativeToInboundDepth() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        assertTrue(service.scheduleFollowThroughNudge(context, "Continue now.", 1));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        assertEquals(2, messageCaptor.getValue().getMetadata()
                .get(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH));
    }

    @Test
    void shouldRefuseFollowThroughNudgeWhenContinuationPromptIsBlank() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        assertFalse(service.scheduleFollowThroughNudge(context, null, 0));
        assertFalse(service.scheduleFollowThroughNudge(context, "   ", 0));
        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldReturnFalseForFollowThroughNudgeWhenContextOrSessionMissing() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        assertFalse(service.scheduleFollowThroughNudge(null, "Continue.", 0));

        AgentContext contextWithoutSession = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        assertFalse(service.scheduleFollowThroughNudge(contextWithoutSession, "Continue.", 0));

        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldPublishAutoProceedAffirmationWithAffirmationPromptAsUserMessage() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        boolean scheduled = service.scheduleAutoProceedAffirmation(context, "Yes, please proceed.", 0);

        assertTrue(scheduled);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals("Yes, please proceed.", message.getContent());
        assertEquals("telegram", message.getChannelType());
        assertEquals("chat-1", message.getChatId());
        assertEquals("internal:auto-proceed", message.getSenderId());
        assertEquals(FIXED_INSTANT, message.getTimestamp());
        assertTrue(message.isInternalMessage());
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY,
                message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        assertEquals(1, message.getMetadata().get(ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH));
        assertEquals("transport-1", message.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertEquals("conversation-1", message.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
        assertEquals("INTERNAL", message.getMetadata().get("trace.root.kind"));
        assertEquals("resilience.auto_proceed.affirmation", message.getMetadata().get("trace.name"));
        assertNotNull(message.getMetadata().get("trace.id"));
        assertNotNull(message.getMetadata().get("trace.span.id"));
    }

    @Test
    void shouldIncrementChainDepthForAutoProceedAffirmationRelativeToInboundDepth() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        assertTrue(service.scheduleAutoProceedAffirmation(context, "Yes.", 1));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        assertEquals(2, messageCaptor.getValue().getMetadata()
                .get(ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH));
    }

    @Test
    void shouldRefuseAutoProceedAffirmationWhenPromptIsBlank() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        assertFalse(service.scheduleAutoProceedAffirmation(context, null, 0));
        assertFalse(service.scheduleAutoProceedAffirmation(context, "   ", 0));
        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldReturnFalseForAutoProceedAffirmationWhenContextOrSessionMissing() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        assertFalse(service.scheduleAutoProceedAffirmation(null, "Yes.", 0));

        AgentContext contextWithoutSession = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        assertFalse(service.scheduleAutoProceedAffirmation(contextWithoutSession, "Yes.", 0));

        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldAttachTraceMetadataToInternalRetryMessage() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

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

        assertTrue(service.scheduleAutoContinueRetry(context, "llm-timeout"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());

        Map<String, Object> metadata = messageCaptor.getValue().getMetadata();
        assertNotNull(metadata.get("trace.id"));
        assertNotNull(metadata.get("trace.span.id"));
        assertEquals("INTERNAL", metadata.get("trace.root.kind"));
        assertEquals("internal.auto_continue", metadata.get("trace.name"));
    }
}
