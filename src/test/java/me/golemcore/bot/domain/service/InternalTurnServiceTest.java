package me.golemcore.bot.domain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

        AgentContext context = context();
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

        AgentContext context = context();
        context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, " ");
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, null);

        boolean scheduled = service.scheduleAutoContinueRetry(context, "llm-timeout");

        assertTrue(scheduled);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertNull(message.getMetadata().get(ContextAttributes.TRANSPORT_CHAT_ID));
        assertNull(message.getMetadata().get(ContextAttributes.CONVERSATION_KEY));
    }

    @Test
    void shouldReturnFalseWhenContextOrSessionMissing() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        assertFalse(service.scheduleAutoContinueRetry(null, "llm-timeout"));
        assertFalse(service.scheduleFollowThroughNudge(null, "Continue.", 0));
        assertFalse(service.scheduleAutoProceedAffirmation(null, "Yes.", 0));

        AgentContext contextWithoutSession = AgentContext.builder()
                .messages(new ArrayList<>())
                .build();
        assertFalse(service.scheduleAutoContinueRetry(contextWithoutSession, "llm-timeout"));
        assertFalse(service.scheduleFollowThroughNudge(contextWithoutSession, "Continue.", 0));
        assertFalse(service.scheduleAutoProceedAffirmation(contextWithoutSession, "Yes.", 0));

        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldPublishFollowThroughNudgeWithContinuationPromptAsUserMessage() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        AgentContext context = contextWithLastUserActivitySequence(7L);
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
        assertEquals(
                "Continue with the concrete next action you just promised, using only the latest user request and visible conversation context. Do not broaden scope. If the action is destructive, asks for credentials, sends external messages, modifies production, or is ambiguous, ask the real user for confirmation.",
                message.getContent());
        assertEquals("telegram", message.getChannelType());
        assertEquals("chat-1", message.getChatId());
        assertEquals("internal:follow-through", message.getSenderId());
        assertEquals(FIXED_INSTANT, message.getTimestamp());
        assertTrue(message.isInternalMessage());
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_CONTINUATION,
                message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        assertEquals(1, message.getMetadata().get(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH));
        assertEquals(7L, message.getMetadata().get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE));
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

        AgentContext context = contextWithLastUserActivitySequence(3L);

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

        AgentContext context = context();

        assertFalse(service.scheduleFollowThroughNudge(context, null, 0));
        assertFalse(service.scheduleFollowThroughNudge(context, "   ", 0));
        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldReturnFalseAndLogWarnWhenFollowThroughDispatchThrows() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        doThrow(new IllegalStateException("queue unavailable"))
                .when(inboundMessageDispatchPort).dispatch(any(Message.class));
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        AttachedAppender attachedAppender = attachAppender();
        try {
            boolean scheduled = service.scheduleFollowThroughNudge(context(), "Continue now.", 0);

            assertFalse(scheduled);
            verify(inboundMessageDispatchPort).dispatch(any(Message.class));
            assertTrue(attachedAppender.appender().list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("failed to schedule follow-through nudge")
                            && event.getFormattedMessage().contains("queue unavailable")));
        } finally {
            detachAppender(attachedAppender);
        }
    }

    @Test
    void shouldPublishAutoProceedAffirmationWithAffirmationPromptAsUserMessage() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        AgentContext context = contextWithLastUserActivitySequence(9L);
        context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, "transport-1");
        context.setAttribute(ContextAttributes.CONVERSATION_KEY, "conversation-1");

        boolean scheduled = service.scheduleAutoProceedAffirmation(context, "Yes, please proceed.", 0);

        assertTrue(scheduled);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        Message message = messageCaptor.getValue();
        assertNotNull(message);
        assertEquals("user", message.getRole());
        assertEquals("Proceed with the single non-destructive next step you just asked to continue.",
                message.getContent());
        assertEquals("telegram", message.getChannelType());
        assertEquals("chat-1", message.getChatId());
        assertEquals("internal:auto-proceed", message.getSenderId());
        assertEquals(FIXED_INSTANT, message.getTimestamp());
        assertTrue(message.isInternalMessage());
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(ContextAttributes.TURN_QUEUE_KIND_INTERNAL_CONTINUATION,
                message.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND));
        assertEquals(1, message.getMetadata().get(ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH));
        assertEquals(9L, message.getMetadata().get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE));
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

        assertTrue(service.scheduleAutoProceedAffirmation(contextWithLastUserActivitySequence(5L), "Yes.", 1));

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

        AgentContext context = context();

        assertFalse(service.scheduleAutoProceedAffirmation(context, null, 0));
        assertFalse(service.scheduleAutoProceedAffirmation(context, "   ", 0));
        verifyNoInteractions(inboundMessageDispatchPort);
    }

    @Test
    void shouldReturnFalseAndLogWarnWhenAutoProceedDispatchThrows() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        doThrow(new IllegalStateException("queue unavailable"))
                .when(inboundMessageDispatchPort).dispatch(any(Message.class));
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        AttachedAppender attachedAppender = attachAppender();
        try {
            boolean scheduled = service.scheduleAutoProceedAffirmation(context(), "Yes, proceed.", 0);

            assertFalse(scheduled);
            verify(inboundMessageDispatchPort).dispatch(any(Message.class));
            assertTrue(attachedAppender.appender().list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("failed to schedule auto-proceed affirmation")
                            && event.getFormattedMessage().contains("queue unavailable")));
        } finally {
            detachAppender(attachedAppender);
        }
    }

    @Test
    void shouldReturnFalseAndLogWarnWhenAutoContinueDispatchThrows() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        doThrow(new IllegalStateException("queue unavailable"))
                .when(inboundMessageDispatchPort).dispatch(any(Message.class));
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        AttachedAppender attachedAppender = attachAppender();
        try {
            boolean scheduled = service.scheduleAutoContinueRetry(context(), "llm-timeout");

            assertFalse(scheduled);
            verify(inboundMessageDispatchPort).dispatch(any(Message.class));
            assertTrue(attachedAppender.appender().list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("failed to schedule auto-continue retry")
                            && event.getFormattedMessage().contains("queue unavailable")));
        } finally {
            detachAppender(attachedAppender);
        }
    }

    @Test
    void shouldAttachTraceMetadataToInternalRetryMessage() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        InternalTurnService service = new InternalTurnService(inboundMessageDispatchPort, clock);

        assertTrue(service.scheduleAutoContinueRetry(context(), "llm-timeout"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());

        Map<String, Object> metadata = messageCaptor.getValue().getMetadata();
        assertNotNull(metadata.get("trace.id"));
        assertNotNull(metadata.get("trace.span.id"));
        assertEquals("INTERNAL", metadata.get("trace.root.kind"));
        assertEquals("internal.auto_continue", metadata.get("trace.name"));
    }

    private AgentContext context() {
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(new ArrayList<>())
                .build();
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }

    private AgentContext contextWithLastUserActivitySequence(long activitySequence) {
        AgentContext context = context();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, activitySequence);
        context.getMessages().add(Message.builder()
                .role("user")
                .content("original request")
                .metadata(metadata)
                .build());
        return context;
    }

    private AttachedAppender attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(InternalTurnService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new AttachedAppender(logger, appender);
    }

    private void detachAppender(AttachedAppender attachedAppender) {
        attachedAppender.logger().detachAppender(attachedAppender.appender());
        attachedAppender.appender().stop();
    }

    private record AttachedAppender(Logger logger, ListAppender<ILoggingEvent> appender) {
    }
}
