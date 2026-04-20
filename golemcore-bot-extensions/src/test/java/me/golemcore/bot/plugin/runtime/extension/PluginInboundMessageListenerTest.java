package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PluginInboundMessageListenerTest {

    @Test
    void shouldMapAndEnqueueInboundPluginMessages() {
        InboundMessageDispatchPort inboundMessageDispatchPort = mock(InboundMessageDispatchPort.class);
        PluginInboundMessageListener listener = new PluginInboundMessageListener(
                inboundMessageDispatchPort,
                new PluginExtensionApiMapper());

        me.golemcore.plugin.api.extension.model.Message pluginMessage = me.golemcore.plugin.api.extension.model.Message
                .builder()
                .channelType("telegram")
                .chatId("42")
                .senderId("user-1")
                .content("hello")
                .metadata(Map.of("transportChatId", "42"))
                .build();

        listener.onInboundMessage(new me.golemcore.plugin.api.extension.loop.AgentLoop.InboundMessageEvent(
                pluginMessage));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(inboundMessageDispatchPort).dispatch(messageCaptor.capture());
        assertEquals("telegram", messageCaptor.getValue().getChannelType());
        assertEquals("42", messageCaptor.getValue().getChatId());
        assertEquals("hello", messageCaptor.getValue().getContent());
        assertEquals("42", messageCaptor.getValue().getMetadata().get("transportChatId"));
    }
}
