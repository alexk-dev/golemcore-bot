package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ResponseRoutingSystemOutgoingResponseTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "chat1";
    private static final String CHANNEL_WEBHOOK = "webhook";

    @Test
    void shouldSendOutgoingResponseTextWhenPresent() {
        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        me.golemcore.bot.domain.service.VoiceResponseHandler voiceHandler = mock(
                me.golemcore.bot.domain.service.VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage(any())).thenReturn("error");

        ResponseRoutingSystem system = new ResponseRoutingSystem(new ChannelRegistry(java.util.List.of(channel)),
                preferences, voiceHandler);
        system.registerChannel(channel);

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        system.process(context);

        verify(channel).sendMessage(eq(CHAT_ID), eq("hello"), any());
        RoutingOutcome outcome1 = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertThat(outcome1).isNotNull();
        assertThat(outcome1.isSentText()).isTrue();
    }

    @Test
    void shouldPreferOutgoingResponseOverLlmResponse() {
        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        me.golemcore.bot.domain.service.VoiceResponseHandler voiceHandler = mock(
                me.golemcore.bot.domain.service.VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage(any())).thenReturn("error");

        ResponseRoutingSystem system = new ResponseRoutingSystem(new ChannelRegistry(java.util.List.of(channel)),
                preferences, voiceHandler);
        system.registerChannel(channel);

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .build();

        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("from-outgoing"));
        // do NOT set LLM_RESPONSE content here; this test is about precedence

        system.process(context);

        verify(channel).sendMessage(eq(CHAT_ID), eq("from-outgoing"), any());
        RoutingOutcome outcome2 = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertThat(outcome2).isNotNull();
        assertThat(outcome2.isSentText()).isTrue();
    }

    @Test
    void shouldRecordRoutingOutcomeAsAttribute() {
        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        me.golemcore.bot.domain.service.VoiceResponseHandler voiceHandler = mock(
                me.golemcore.bot.domain.service.VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage(any())).thenReturn("error");

        ResponseRoutingSystem system = new ResponseRoutingSystem(new ChannelRegistry(java.util.List.of(channel)),
                preferences, voiceHandler);

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertThat(outcome).isNotNull();
        assertThat(outcome.isAttempted()).isTrue();
        assertThat(outcome.isSentText()).isTrue();
    }

    @Test
    void shouldMirrorWebhookAgentResponseToConfiguredDeliveryChannel() {
        ChannelPort webhookChannel = mock(ChannelPort.class);
        when(webhookChannel.getChannelType()).thenReturn(CHANNEL_WEBHOOK);
        when(webhookChannel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(webhookChannel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        ChannelPort telegramChannel = mock(ChannelPort.class);
        when(telegramChannel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(telegramChannel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(telegramChannel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        me.golemcore.bot.domain.service.VoiceResponseHandler voiceHandler = mock(
                me.golemcore.bot.domain.service.VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage(any())).thenReturn("error");

        ResponseRoutingSystem system = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(webhookChannel, telegramChannel)),
                preferences,
                voiceHandler);

        Message inbound = Message.builder()
                .role("user")
                .channelType(CHANNEL_WEBHOOK)
                .chatId("hook:test-run")
                .timestamp(Instant.now())
                .metadata(Map.of(
                        "webhook.deliver", true,
                        "webhook.deliver.channel", CHANNEL_TYPE,
                        "webhook.deliver.to", "tg-user-42"))
                .build();

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_WEBHOOK)
                .chatId("hook:test-run")
                .messages(List.of(inbound))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(List.of(inbound))
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        system.process(context);

        verify(webhookChannel).sendMessage(eq("hook:test-run"), eq("hello"), any());
        verify(telegramChannel).sendMessage(eq("tg-user-42"), eq("hello"), any());
    }

    @Test
    void shouldKeepWebhookTextRoutingWhenConfiguredDeliveryChannelIsMissing() {
        ChannelPort webhookChannel = mock(ChannelPort.class);
        when(webhookChannel.getChannelType()).thenReturn(CHANNEL_WEBHOOK);
        when(webhookChannel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(webhookChannel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        me.golemcore.bot.domain.service.VoiceResponseHandler voiceHandler = mock(
                me.golemcore.bot.domain.service.VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage(any())).thenReturn("error");

        ResponseRoutingSystem system = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(webhookChannel)),
                preferences,
                voiceHandler);

        Message inbound = Message.builder()
                .role("user")
                .channelType(CHANNEL_WEBHOOK)
                .chatId("hook:test-run")
                .timestamp(Instant.now())
                .metadata(Map.of(
                        "webhook.deliver", true,
                        "webhook.deliver.channel", CHANNEL_TYPE,
                        "webhook.deliver.to", "tg-user-42"))
                .build();

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_WEBHOOK)
                .chatId("hook:test-run")
                .messages(List.of(inbound))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(List.of(inbound))
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        system.process(context);

        verify(webhookChannel).sendMessage(eq("hook:test-run"), eq("hello"), any());
        RoutingOutcome outcome = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        assertThat(outcome).isNotNull();
        assertThat(outcome.isSentText()).isTrue();
    }

    @Test
    void shouldSendWebhookAttachmentsToConfiguredDeliveryChannelOnly() {
        ChannelPort webhookChannel = mock(ChannelPort.class);
        when(webhookChannel.getChannelType()).thenReturn(CHANNEL_WEBHOOK);
        when(webhookChannel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(webhookChannel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        ChannelPort telegramChannel = mock(ChannelPort.class);
        when(telegramChannel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(telegramChannel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(telegramChannel.sendMessage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(telegramChannel.sendDocument(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        me.golemcore.bot.domain.service.VoiceResponseHandler voiceHandler = mock(
                me.golemcore.bot.domain.service.VoiceResponseHandler.class);
        when(voiceHandler.isAvailable()).thenReturn(false);

        UserPreferencesService preferences = mock(UserPreferencesService.class);
        when(preferences.getMessage(any())).thenReturn("error");

        ResponseRoutingSystem system = new ResponseRoutingSystem(
                new ChannelRegistry(List.of(webhookChannel, telegramChannel)),
                preferences,
                voiceHandler);

        Message inbound = Message.builder()
                .role("user")
                .channelType(CHANNEL_WEBHOOK)
                .chatId("hook:test-run")
                .timestamp(Instant.now())
                .metadata(Map.of(
                        "webhook.deliver", true,
                        "webhook.deliver.channel", CHANNEL_TYPE,
                        "webhook.deliver.to", "tg-user-42"))
                .build();

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_WEBHOOK)
                .chatId("hook:test-run")
                .messages(List.of(inbound))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(List.of(inbound))
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.builder()
                .attachment(Attachment.builder()
                        .type(Attachment.Type.DOCUMENT)
                        .data(new byte[] { 1, 2, 3 })
                        .filename("report.pdf")
                        .mimeType("application/pdf")
                        .caption("Report")
                        .build())
                .build());

        system.process(context);

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(telegramChannel).sendDocument(eq("tg-user-42"), payloadCaptor.capture(), eq("report.pdf"),
                eq("Report"));
        assertThat(payloadCaptor.getValue()).containsExactly(1, 2, 3);
        verify(webhookChannel, never()).sendDocument(any(), any(), any(), any());
    }
}
