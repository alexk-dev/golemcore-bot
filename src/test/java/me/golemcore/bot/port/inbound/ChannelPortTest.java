package me.golemcore.bot.port.inbound;

import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelPortTest {

    @Test
    void shouldDisableVoiceResponsesByDefault() {
        ChannelPort channelPort = new ChannelPort() {
            @Override
            public String getChannelType() {
                return "test";
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public CompletableFuture<Void> sendMessage(String chatId, String content) {
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

            @Override
            public boolean isAuthorized(String senderId) {
                return true;
            }

            @Override
            public void onMessage(Consumer<Message> handler) {
            }
        };

        assertFalse(channelPort.isVoiceResponseEnabled());
    }

    @Test
    void shouldDisableProactiveDocumentsByDefault() {
        ChannelPort channelPort = basicChannel(true);

        assertTrue(channelPort.supportsProactiveMessage("chat-1"));
        assertFalse(channelPort.supportsProactiveDocument("chat-1"));
        assertFalse(basicChannel(false).supportsProactiveDocument("chat-1"));
    }

    @Test
    void shouldEnableProactiveDocumentsWhenChannelOverridesDocumentDelivery() {
        ChannelPort channelPort = new ChannelPort() {
            @Override
            public String getChannelType() {
                return "document";
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public CompletableFuture<Void> sendMessage(String chatId, String content) {
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

            @Override
            public CompletableFuture<Void> sendDocument(String chatId, byte[] fileData, String filename,
                    String caption) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean supportsDocumentDelivery() {
                return true;
            }

            @Override
            public CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean isAuthorized(String senderId) {
                return true;
            }

            @Override
            public void onMessage(Consumer<Message> handler) {
            }
        };

        assertTrue(channelPort.supportsDocumentDelivery());
        assertTrue(channelPort.supportsProactiveDocument("chat-1"));
    }

    @Test
    void shouldIgnoreDefaultTypingIndicator() {
        assertDoesNotThrow(() -> basicChannel(true).showTyping("chat-1"));
    }

    private ChannelPort basicChannel(boolean running) {
        return new ChannelPort() {
            @Override
            public String getChannelType() {
                return "test";
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public CompletableFuture<Void> sendMessage(String chatId, String content) {
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

            @Override
            public boolean isAuthorized(String senderId) {
                return true;
            }

            @Override
            public void onMessage(Consumer<Message> handler) {
            }
        };
    }
}
