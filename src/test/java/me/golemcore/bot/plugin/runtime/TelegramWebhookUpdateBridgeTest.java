package me.golemcore.bot.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramWebhookUpdateBridgeTest {

    @Test
    void shouldSerializeTelegramUpdateBeforeDispatchingToRegistry() {
        TelegramWebhookUpdateConsumerRegistry registry = mock(TelegramWebhookUpdateConsumerRegistry.class);
        TelegramWebhookUpdateBridge bridge = new TelegramWebhookUpdateBridge(registry, new ObjectMapper());
        when(registry.dispatch("{\"update_id\":7}")).thenReturn(CompletableFuture.completedFuture(null));

        Update update = new Update();
        update.setUpdateId(7);

        CompletableFuture<Void> result = bridge.dispatch(update);

        assertNotNull(result);
        result.join();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(registry).dispatch(captor.capture());
        assertEquals("{\"update_id\":7}", captor.getValue());
    }
}
