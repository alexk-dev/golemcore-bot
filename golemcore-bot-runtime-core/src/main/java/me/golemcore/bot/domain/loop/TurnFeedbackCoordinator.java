package me.golemcore.bot.domain.loop;

import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
class TurnFeedbackCoordinator {

    private static final long TYPING_INTERVAL_SECONDS = 4;

    private final ChannelRuntimePort channelRuntimePort;
    private final UserPreferencesService preferencesService;
    private final ScheduledExecutorService typingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "typing-indicator");
        thread.setDaemon(true);
        return thread;
    });

    TurnFeedbackCoordinator(ChannelRuntimePort channelRuntimePort, UserPreferencesService preferencesService) {
        this.channelRuntimePort = Objects.requireNonNull(channelRuntimePort, "channelRuntimePort must not be null");
        this.preferencesService = Objects.requireNonNull(preferencesService, "preferencesService must not be null");
    }

    void shutdown() {
        typingExecutor.shutdownNow();
        try {
            typingExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    TypingHandle startTyping(Message message) {
        if (message == null || message.getChannelType() == null || message.getChannelType().isBlank()) {
            return TypingHandle.noop();
        }
        ChannelDeliveryPort channel = channelRuntimePort.findChannel(message.getChannelType()).orElse(null);
        String chatId = resolveTransportChatId(message);
        if (channel == null || chatId == null || chatId.isBlank()) {
            return TypingHandle.noop();
        }

        sendTypingIndicator(channel, chatId);
        ScheduledFuture<?> task = typingExecutor.scheduleAtFixedRate(() -> sendTypingIndicator(channel, chatId),
                TYPING_INTERVAL_SECONDS, TYPING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return new TypingHandle(task);
    }

    void notifyRateLimited(Message message) {
        if (message == null || message.getChannelType() == null || message.getChannelType().isBlank()) {
            return;
        }

        String chatId = resolveTransportChatId(message);
        if (chatId == null || chatId.isBlank()) {
            return;
        }

        ChannelDeliveryPort channel = channelRuntimePort.findChannel(message.getChannelType()).orElse(null);
        if (channel == null) {
            return;
        }

        String text = preferencesService.getMessage("system.rate.limit");
        if (text == null || text.isBlank()) {
            text = "Rate limit exceeded. Please wait before sending more messages.";
        }

        try {
            channel.sendMessage(chatId, text);
        } catch (Exception e) { // NOSONAR - user feedback must not break request handling
            log.debug("Rate limit notification failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    private void sendTypingIndicator(ChannelDeliveryPort channel, String chatId) {
        try {
            channel.showTyping(chatId);
        } catch (Exception e) {
            log.debug("Typing indicator failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    private String resolveTransportChatId(Message message) {
        String transportChatId = readMetadataString(message, ContextAttributes.TRANSPORT_CHAT_ID);
        if (transportChatId != null && !transportChatId.isBlank()) {
            return transportChatId;
        }
        return message != null ? message.getChatId() : null;
    }

    private String readMetadataString(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return null;
        }
        return AutoRunContextSupport.readMetadataString(message.getMetadata(), key);
    }

    record TypingHandle(ScheduledFuture<?> task) implements AutoCloseable {

        private static TypingHandle noop() {
            return new TypingHandle(null);
        }

        @Override
        public void close() {
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}
