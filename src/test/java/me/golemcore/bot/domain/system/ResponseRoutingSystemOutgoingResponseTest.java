package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ResponseRoutingSystemOutgoingResponseTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "chat1";

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

        ResponseRoutingSystem system = new ResponseRoutingSystem(java.util.List.of(channel), preferences, voiceHandler);
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
        RoutingOutcome outcome1 = context.getAttribute("routing.outcome");
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

        ResponseRoutingSystem system = new ResponseRoutingSystem(java.util.List.of(channel), preferences, voiceHandler);
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
        RoutingOutcome outcome2 = context.getAttribute("routing.outcome");
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

        ResponseRoutingSystem system = new ResponseRoutingSystem(java.util.List.of(channel), preferences, voiceHandler);

        AgentSession session = AgentSession.builder()
                .channelType(CHANNEL_TYPE)
                .chatId(CHAT_ID)
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .build();
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("hello"));

        system.process(context);

        RoutingOutcome outcome = context.getAttribute("routing.outcome");
        assertThat(outcome).isNotNull();
        assertThat(outcome.isAttempted()).isTrue();
        assertThat(outcome.isSentText()).isTrue();
    }
}
