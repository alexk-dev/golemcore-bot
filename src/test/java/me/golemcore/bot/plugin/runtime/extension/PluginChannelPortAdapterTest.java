package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginChannelPortAdapterTest {

    @Test
    void shouldForwardLifecycleOutboundCallsAndInboundMessages() {
        me.golemcore.plugin.api.extension.port.inbound.ChannelPort delegate = mock(
                me.golemcore.plugin.api.extension.port.inbound.ChannelPort.class);
        PluginChannelPortAdapter adapter = new PluginChannelPortAdapter(delegate, new PluginExtensionApiMapper());
        CompletableFuture<Void> completed = CompletableFuture.completedFuture(null);

        when(delegate.getChannelType()).thenReturn("telegram");
        when(delegate.isRunning()).thenReturn(true);
        when(delegate.sendMessage("42", "hello")).thenReturn(completed);
        when(delegate.sendMessage("42", "hello", Map.of("parseMode", "markdown"))).thenReturn(completed);
        when(delegate.sendMessage(any(me.golemcore.plugin.api.extension.model.Message.class))).thenReturn(completed);
        when(delegate.sendVoice(org.mockito.ArgumentMatchers.eq("42"), any(byte[].class))).thenReturn(completed);
        when(delegate.isAuthorized("user-1")).thenReturn(true);
        when(delegate.sendPhoto(org.mockito.ArgumentMatchers.eq("42"), any(byte[].class),
                org.mockito.ArgumentMatchers.eq("photo.png"), org.mockito.ArgumentMatchers.eq("caption")))
                .thenReturn(completed);
        when(delegate.sendDocument(org.mockito.ArgumentMatchers.eq("42"), any(byte[].class),
                org.mockito.ArgumentMatchers.eq("report.pdf"), org.mockito.ArgumentMatchers.eq("doc")))
                .thenReturn(completed);

        assertEquals("telegram", adapter.getChannelType());
        adapter.start();
        adapter.stop();
        assertTrue(adapter.isRunning());
        adapter.sendMessage("42", "hello").join();
        adapter.sendMessage("42", "hello", Map.of("parseMode", "markdown")).join();

        Message message = Message.builder()
                .channelType("telegram")
                .chatId("42")
                .content("outbound")
                .metadata(new LinkedHashMap<>(Map.of("transportChatId", "42")))
                .build();
        adapter.sendMessage(message).join();
        adapter.sendVoice("42", new byte[] { 1, 2, 3 }).join();
        assertTrue(adapter.isAuthorized("user-1"));
        adapter.sendPhoto("42", new byte[] { 7, 8 }, "photo.png", "caption").join();
        adapter.sendDocument("42", new byte[] { 9 }, "report.pdf", "doc").join();
        adapter.showTyping("42");

        ArgumentCaptor<me.golemcore.plugin.api.extension.model.Message> sentMessageCaptor = ArgumentCaptor.forClass(
                me.golemcore.plugin.api.extension.model.Message.class);
        verify(delegate).sendMessage(sentMessageCaptor.capture());
        assertEquals("outbound", sentMessageCaptor.getValue().getContent());
        assertEquals("42", sentMessageCaptor.getValue().getMetadata().get("transportChatId"));

        AtomicReference<Message> received = new AtomicReference<>();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Consumer> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        adapter.onMessage(received::set);
        verify(delegate).onMessage(consumerCaptor.capture());

        @SuppressWarnings("unchecked")
        Consumer<me.golemcore.plugin.api.extension.model.Message> pluginConsumer = consumerCaptor.getValue();
        pluginConsumer.accept(me.golemcore.plugin.api.extension.model.Message.builder()
                .channelType("telegram")
                .chatId("42")
                .content("inbound")
                .build());

        assertEquals("inbound", received.get().getContent());
        assertEquals("42", received.get().getChatId());
        verify(delegate).start();
        verify(delegate).stop();
        verify(delegate).showTyping("42");
    }
}
