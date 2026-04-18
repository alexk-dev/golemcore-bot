package me.golemcore.bot.domain.system.toolloop.resilience;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SuspendedTurnManagerTest {

    private static final Instant NOW = Instant.parse("2026-04-16T02:30:00Z");

    @Test
    void shouldScheduleColdRetryAsInternalTurnForResolvedSessionIdentity() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        RuntimeConfig.ResilienceConfig config = RuntimeConfig.ResilienceConfig.builder()
                .coldRetryMaxAttempts(3)
                .build();

        String message = manager.suspend(context, "llm.langchain4j.internal_server", config);

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        DelayedSessionAction action = captor.getValue();
        assertEquals("telegram", action.getChannelType());
        assertEquals("conv_1", action.getConversationKey());
        assertEquals("transport-1", action.getTransportChatId());
        assertEquals(DelayedActionKind.RETRY_LLM_TURN, action.getKind());
        assertEquals(DelayedActionDeliveryMode.INTERNAL_TURN, action.getDeliveryMode());
        assertEquals(NOW.plusSeconds(120), action.getRunAt());
        assertEquals(3, action.getMaxAttempts());
        assertTrue(action.isCancelOnUserActivity());
        assertEquals("session-1", action.getPayload().get("sessionId"));
        assertEquals("llm.langchain4j.internal_server", action.getPayload().get("errorCode"));
        assertEquals(0, action.getPayload().get("resumeAttempt"));
        assertEquals("llm retry", action.getPayload().get("originalPrompt"));
        assertTrue(message.contains("llm.langchain4j.internal_server"));
        assertTrue(message.contains("2 minute"));
    }

    @Test
    void shouldCapColdRetryDelayAtLastScheduleEntry() {
        SuspendedTurnManager manager = new SuspendedTurnManager(mock(DelayedSessionActionService.class), fixedClock());

        assertEquals(120, manager.computeDelay(0));
        assertEquals(300, manager.computeDelay(1));
        assertEquals(900, manager.computeDelay(2));
        assertEquals(3600, manager.computeDelay(3));
        assertEquals(3600, manager.computeDelay(99));
    }

    @Test
    void shouldScheduleNextColdRetryDelayFromResumeAttemptMetadata() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        context.setAttribute(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT, 1);

        String message = manager.suspend(context, "llm.request.timeout",
                RuntimeConfig.ResilienceConfig.builder().coldRetryMaxAttempts(4).build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        DelayedSessionAction action = captor.getValue();
        assertEquals(1, action.getPayload().get("resumeAttempt"));
        assertEquals(NOW.plusSeconds(300), action.getRunAt());
        assertTrue(message.contains("5 minute"));
    }

    @Test
    void shouldRejectColdRetryWhenResumeAttemptsAreExhausted() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        context.setAttribute(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT, 3);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> manager.suspend(context, "llm.request.timeout",
                        RuntimeConfig.ResilienceConfig.builder().coldRetryMaxAttempts(3).build()));

        assertTrue(exception.getMessage().contains("Cold retry attempts exhausted"));
        verifyNoInteractions(actionService);
    }

    @Test
    void shouldScheduleColdRetryWithUnknownIdentityWhenContextHasNoSession() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = AgentContext.builder().build();

        String message = manager.suspend(context, "llm.request.timeout",
                RuntimeConfig.ResilienceConfig.builder().coldRetryMaxAttempts(2).build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        DelayedSessionAction action = captor.getValue();
        assertEquals("unknown", action.getChannelType());
        assertEquals("unknown", action.getConversationKey());
        assertEquals("unknown", action.getTransportChatId());
        assertEquals("resilience-cold-retry:unknown", action.getDedupeKey());
        assertEquals(2, action.getMaxAttempts());
        assertFalse(action.getPayload().containsKey("sessionId"));
        assertTrue(message.contains("llm.request.timeout"));
    }

    @Test
    void shouldIgnoreInternalAndBlankMessagesWhenCapturingOriginalPrompt() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        Message internalUser = Message.builder()
                .role("user")
                .content("internal")
                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                .timestamp(NOW)
                .build();
        Message blankUser = Message.builder().role("user").content(" ").timestamp(NOW).build();
        Message assistant = Message.builder().role("assistant").content("answer").timestamp(NOW).build();
        context.setMessages(new ArrayList<>(java.util.List.of(blankUser, assistant, internalUser)));

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertFalse(captor.getValue().getPayload().containsKey("originalPrompt"));
    }

    @Test
    void shouldCapturePromptWhenBlankAndInternalMessagesPrecedeVisibleUser() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        Message visible = Message.builder().role("user").content("visible prompt").timestamp(NOW).build();
        Message internalUser = Message.builder()
                .role("user")
                .content("internal")
                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                .timestamp(NOW)
                .build();
        Message blankUser = Message.builder().role("user").content(" ").timestamp(NOW).build();
        Message nullMessage = null;
        context.setMessages(new ArrayList<>());
        context.getMessages().add(visible);
        context.getMessages().add(nullMessage);
        context.getMessages().add(blankUser);
        context.getMessages().add(internalUser);

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertEquals("visible prompt", captor.getValue().getPayload().get("originalPrompt"));
    }

    @Test
    void shouldFallbackBlankIdentityValuesToUnknown() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentSession session = AgentSession.builder()
                .id("session-blank")
                .channelType(" ")
                .chatId(" ")
                .metadata(new LinkedHashMap<>())
                .build();
        AgentContext context = AgentContext.builder().session(session).messages(new ArrayList<>()).build();

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertEquals("unknown", captor.getValue().getChannelType());
        assertEquals("unknown", captor.getValue().getConversationKey());
        assertEquals("unknown", captor.getValue().getTransportChatId());
    }

    @Test
    void shouldHandleNullContextWhenCapturingPrompt() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());

        manager.suspend(null, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertFalse(captor.getValue().getPayload().containsKey("originalPrompt"));
    }

    @Test
    void shouldUseConversationKeyInDedupeWhenSessionIdIsBlank() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        context.getSession().setId(" ");

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        DelayedSessionAction action = captor.getValue();
        assertEquals("resilience-cold-retry:conv_1", action.getDedupeKey());
        assertFalse(action.getPayload().containsKey("sessionId"));
    }

    @Test
    void shouldCapturePromptFromUserMessageWithoutMetadata() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        Message user = Message.builder().role("user").content("plain prompt").timestamp(NOW).build();
        context.setMessages(new ArrayList<>(java.util.List.of(user)));

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertEquals("plain prompt", captor.getValue().getPayload().get("originalPrompt"));
    }

    @Test
    void shouldIgnoreNullMessageListWhenCapturingOriginalPrompt() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        context.setMessages(null);

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertFalse(captor.getValue().getPayload().containsKey("originalPrompt"));
    }

    @Test
    void shouldFallbackPromptWhenNoUserMessageExists() {
        DelayedSessionActionService actionService = mock(DelayedSessionActionService.class);
        SuspendedTurnManager manager = new SuspendedTurnManager(actionService, fixedClock());
        AgentContext context = contextWithIdentity();
        context.setMessages(new ArrayList<>());

        manager.suspend(context, "llm.request.timeout", RuntimeConfig.ResilienceConfig.builder().build());

        ArgumentCaptor<DelayedSessionAction> captor = ArgumentCaptor.forClass(DelayedSessionAction.class);
        verify(actionService).schedule(captor.capture());
        assertFalse(captor.getValue().getPayload().containsKey("originalPrompt"));
    }

    private AgentContext contextWithIdentity() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.CONVERSATION_KEY, "conv_1");
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, "transport-1");
        AgentSession session = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("transport-1")
                .metadata(metadata)
                .build();
        Message user = Message.builder()
                .role("user")
                .content("llm retry")
                .timestamp(NOW)
                .build();
        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(java.util.List.of(user)))
                .build();
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
