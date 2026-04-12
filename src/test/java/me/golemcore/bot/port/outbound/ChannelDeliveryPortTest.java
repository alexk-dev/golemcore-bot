package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelDeliveryPortTest {

    @Test
    void shouldUseDefaultOptionalDeliveryCapabilities() {
        RecordingDeliveryPort port = new RecordingDeliveryPort();

        port.sendMessage("chat-1", "hello", Map.of("silent", true)).join();
        port.sendRuntimeEvent("chat-1", RuntimeEvent.builder().build()).join();
        port.sendProgressUpdate("chat-1", new ProgressUpdate(null, "progress", null)).join();
        port.sendPhoto("chat-1", new byte[] { 1 }, "image.png", "caption").join();
        port.sendDocument("chat-1", new byte[] { 2 }, "doc.txt", "caption").join();
        port.showTyping("chat-1");

        assertEquals("test", port.getChannelType());
        assertEquals("chat-1:hello", port.lastTextMessage);
        assertFalse(port.isVoiceResponseEnabled());
        assertTrue(port.supportsProactiveMessage("chat-1"));
        assertFalse(port.supportsDocumentDelivery());
        assertFalse(port.supportsProactiveDocument("chat-1"));
    }

    private static final class RecordingDeliveryPort implements ChannelDeliveryPort {

        private String lastTextMessage;

        @Override
        public String getChannelType() {
            return "test";
        }

        @Override
        public CompletableFuture<Void> sendMessage(String chatId, String content) {
            lastTextMessage = chatId + ":" + content;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendMessage(Message message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
