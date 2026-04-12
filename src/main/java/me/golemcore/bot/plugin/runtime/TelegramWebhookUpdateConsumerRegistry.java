package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.TelegramWebhookUpdateConsumer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Runtime registry for Telegram webhook consumers contributed by plugins.
 */
@Component
public class TelegramWebhookUpdateConsumerRegistry {

    private final Map<String, List<TelegramWebhookUpdateConsumer>> consumersByPlugin = new LinkedHashMap<>();

    public synchronized void replaceConsumers(String pluginId, Collection<TelegramWebhookUpdateConsumer> consumers) {
        consumersByPlugin.put(pluginId, new ArrayList<>(consumers));
    }

    public synchronized void removeConsumers(String pluginId) {
        consumersByPlugin.remove(pluginId);
    }

    public synchronized CompletableFuture<Void> dispatch(String updateJson) {
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        for (List<TelegramWebhookUpdateConsumer> consumers : consumersByPlugin.values()) {
            for (TelegramWebhookUpdateConsumer consumer : consumers) {
                try {
                    CompletableFuture<Void> delivery = consumer.acceptUpdate(updateJson);
                    if (delivery != null) {
                        deliveries.add(delivery);
                    }
                } catch (RuntimeException ex) {
                    CompletableFuture<Void> failed = new CompletableFuture<>();
                    failed.completeExceptionally(ex);
                    deliveries.add(failed);
                }
            }
        }
        if (deliveries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(deliveries.toArray(new CompletableFuture[0]));
    }

    public synchronized int consumerCount() {
        return consumersByPlugin.values().stream()
                .mapToInt(List::size)
                .sum();
    }
}
