package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayedActionDispatcherTest {

    @Test
    void shouldDispatchDirectMessage() throws Exception {
        SessionRunCoordinator sessionRunCoordinator = mock(SessionRunCoordinator.class);
        ToolArtifactService toolArtifactService = mock(ToolArtifactService.class);
        DelayedActionPolicyService policyService = mock(DelayedActionPolicyService.class);
        ChannelPort channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn("telegram");
        when(channelPort.isRunning()).thenReturn(true);
        when(channelPort.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(policyService.supportsProactiveMessage("telegram", "chat-1")).thenReturn(true);

        DelayedActionDispatcher dispatcher = new DelayedActionDispatcher(
                sessionRunCoordinator,
                new ChannelRegistry(List.of(channelPort)),
                toolArtifactService,
                policyService,
                Clock.fixed(Instant.parse("2026-03-19T18:30:00Z"), ZoneOffset.UTC));

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-1")
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .deliveryMode(DelayedActionDeliveryMode.DIRECT_MESSAGE)
                .payload(Map.of("message", "Reminder"))
                .build();

        DelayedActionDispatcher.DispatchResult result = dispatcher.dispatch(action).get();

        assertTrue(result.success());
        verify(channelPort).sendMessage("chat-1", "Reminder");
    }

    @Test
    void shouldSubmitInternalTurnWithDelayedMetadata() throws Exception {
        SessionRunCoordinator sessionRunCoordinator = mock(SessionRunCoordinator.class);
        ToolArtifactService toolArtifactService = mock(ToolArtifactService.class);
        DelayedActionPolicyService policyService = mock(DelayedActionPolicyService.class);
        when(sessionRunCoordinator.submit(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(policyService.supportsDelayedExecution("telegram", "chat-1")).thenReturn(true);

        DelayedActionDispatcher dispatcher = new DelayedActionDispatcher(
                sessionRunCoordinator,
                new ChannelRegistry(List.of()),
                toolArtifactService,
                policyService,
                Clock.fixed(Instant.parse("2026-03-19T18:30:00Z"), ZoneOffset.UTC));

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-1")
                .channelType("telegram")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .runAt(Instant.parse("2026-03-19T18:35:00Z"))
                .payload(Map.of("instruction", "Start the report"))
                .build();

        DelayedActionDispatcher.DispatchResult result = dispatcher.dispatch(action).get();

        assertTrue(result.success());
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sessionRunCoordinator).submit(captor.capture());
        Message message = captor.getValue();
        assertEquals("conv-1", message.getChatId());
        assertEquals(true, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_DELAYED_ACTION,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals("delay-1", message.getMetadata().get(ContextAttributes.DELAYED_ACTION_ID));
    }

    @Test
    void shouldRejectInternalTurnForUnsupportedWebhookChannel() throws Exception {
        SessionRunCoordinator sessionRunCoordinator = mock(SessionRunCoordinator.class);
        ToolArtifactService toolArtifactService = mock(ToolArtifactService.class);
        DelayedActionPolicyService policyService = mock(DelayedActionPolicyService.class);
        when(policyService.supportsDelayedExecution("webhook", "chat-1")).thenReturn(false);
        when(policyService.isChannelSupported("webhook")).thenReturn(false);

        DelayedActionDispatcher dispatcher = new DelayedActionDispatcher(
                sessionRunCoordinator,
                new ChannelRegistry(List.of()),
                toolArtifactService,
                policyService,
                Clock.fixed(Instant.parse("2026-03-19T18:30:00Z"), ZoneOffset.UTC));

        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("delay-1")
                .channelType("webhook")
                .conversationKey("conv-1")
                .transportChatId("chat-1")
                .kind(DelayedActionKind.RUN_LATER)
                .deliveryMode(DelayedActionDeliveryMode.INTERNAL_TURN)
                .payload(Map.of("instruction", "Start the report"))
                .build();

        DelayedActionDispatcher.DispatchResult result = dispatcher.dispatch(action).get();

        assertFalse(result.success());
        assertFalse(result.retryable());
        verify(sessionRunCoordinator, never()).submit(any(Message.class));
    }
}
