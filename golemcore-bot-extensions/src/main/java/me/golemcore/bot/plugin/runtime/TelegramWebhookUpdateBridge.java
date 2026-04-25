package me.golemcore.bot.plugin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.concurrent.CompletableFuture;

/**
 * Bridges Telegram webhook updates from the bot HTTP layer into plugin
 * consumers.
 */
@Component
@RequiredArgsConstructor
public class TelegramWebhookUpdateBridge {

    private final TelegramWebhookUpdateConsumerRegistry consumerRegistry;
    private final ObjectMapper objectMapper;

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
