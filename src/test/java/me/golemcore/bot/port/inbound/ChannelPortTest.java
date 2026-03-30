package me.golemcore.bot.port.inbound;

import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
