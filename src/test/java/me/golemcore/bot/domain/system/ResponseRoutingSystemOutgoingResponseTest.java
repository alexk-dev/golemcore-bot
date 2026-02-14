package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResponseRoutingSystemOutgoingResponseTest {

    private static final String CHANNEL_TYPE = "telegram";
    private static final String CHAT_ID = "chat1";

    @Test
    void shouldSendOutgoingResponseTextWhenPresent() {
        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

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

        verify(channel).sendMessage(CHAT_ID, "hello");
        assertThat((Object) context.getAttribute(ContextAttributes.RESPONSE_SENT)).isEqualTo(true);
    }

    @Test
    void shouldPreferOutgoingResponseOverLlmResponse() {
        ChannelPort channel = mock(ChannelPort.class);
        when(channel.getChannelType()).thenReturn(CHANNEL_TYPE);
        when(channel.sendMessage(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

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

        verify(channel).sendMessage(CHAT_ID, "from-outgoing");
        assertThat((Object) context.getAttribute(ContextAttributes.RESPONSE_SENT)).isEqualTo(true);
    }
}
