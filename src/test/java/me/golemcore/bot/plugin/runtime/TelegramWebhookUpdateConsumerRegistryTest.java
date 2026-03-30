package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.TelegramWebhookUpdateConsumer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramWebhookUpdateConsumerRegistryTest {

    @Test
    void shouldDispatchTelegramWebhookUpdateToAllRegisteredConsumers() {
        TelegramWebhookUpdateConsumer consumer = mock(TelegramWebhookUpdateConsumer.class);
        when(consumer.acceptUpdate(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        TelegramWebhookUpdateConsumerRegistry registry = new TelegramWebhookUpdateConsumerRegistry();
        registry.replaceConsumers("plugin-a", List.of(consumer));

        registry.dispatch("{\"update_id\":11}").join();

        verify(consumer).acceptUpdate("{\"update_id\":11}");
    }

    @Test
    void shouldRemoveConsumersWhenPluginIsUnloaded() {
        TelegramWebhookUpdateConsumer consumer = mock(TelegramWebhookUpdateConsumer.class);
        TelegramWebhookUpdateConsumerRegistry registry = new TelegramWebhookUpdateConsumerRegistry();
        registry.replaceConsumers("plugin-a", List.of(consumer));

        registry.removeConsumers("plugin-a");

        assertTrue(registry.dispatch("{\"update_id\":11}").isDone());
        assertEquals(0, registry.consumerCount());
    }
}
