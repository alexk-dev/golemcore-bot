package me.golemcore.bot.port.outbound;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ProgressUpdate;
import me.golemcore.bot.domain.model.RuntimeEvent;

public interface ChannelDeliveryPort {

    String getChannelType();

    CompletableFuture<Void> sendMessage(String chatId, String content);

    default CompletableFuture<Void> sendMessage(String chatId, String content, Map<String, Object> hints) {
        return sendMessage(chatId, content);
    }

    CompletableFuture<Void> sendMessage(Message message);

    CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData);

    default boolean isVoiceResponseEnabled() {
        return false;
    }

    default CompletableFuture<Void> sendRuntimeEvent(String chatId, RuntimeEvent event) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> sendProgressUpdate(String chatId, ProgressUpdate update) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<Void> sendPhoto(String chatId, byte[] imageData,
            String filename, String caption) {
        return sendDocument(chatId, imageData, filename, caption);
    }

    default CompletableFuture<Void> sendDocument(String chatId, byte[] fileData,
            String filename, String caption) {
        return CompletableFuture.completedFuture(null);
    }

    default boolean supportsProactiveMessage(String chatId) {
        return true;
    }

    default boolean supportsProactiveDocument(String chatId) {
        return supportsProactiveMessage(chatId) && supportsDocumentDelivery();
    }

    default boolean supportsDocumentDelivery() {
        return false;
    }

    default void showTyping(String chatId) {
        // Default no-op implementation
    }
}
