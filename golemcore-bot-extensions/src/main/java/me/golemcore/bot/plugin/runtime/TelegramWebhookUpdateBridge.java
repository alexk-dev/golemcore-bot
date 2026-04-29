package me.golemcore.bot.plugin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.concurrent.CompletableFuture;

/**
 * Bridges Telegram webhook updates from the bot HTTP layer into plugin
 * consumers.
 */
@Component
public class TelegramWebhookUpdateBridge {

    private final TelegramWebhookUpdateConsumerRegistry consumerRegistry;
    private final ObjectMapper objectMapper;

    public TelegramWebhookUpdateBridge(
            TelegramWebhookUpdateConsumerRegistry consumerRegistry,
            ObjectMapper objectMapper) {
        this.consumerRegistry = consumerRegistry;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<Void> dispatch(Update update) {
        if (update == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            String payload = objectMapper.writeValueAsString(update);
            return consumerRegistry.dispatch(payload);
        } catch (JsonProcessingException ex) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }
}
