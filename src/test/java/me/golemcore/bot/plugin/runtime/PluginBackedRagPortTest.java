package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.RagProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginBackedRagPortTest {

    @Test
    void shouldReturnFallbackValuesWhenNoProviderAvailable() {
        PluginBackedRagPort port = new PluginBackedRagPort(new RagProviderRegistry());

        assertEquals("", port.query("hello").join());
        assertNull(port.index("hello").join());
        assertFalse(port.isAvailable());
        assertEquals(50, port.getIndexMinLength());
    }

    @Test
    void shouldDelegateToFirstAvailableProvider() {
        RagProvider unavailable = mock(RagProvider.class);
        RagProvider available = mock(RagProvider.class);
        when(unavailable.isAvailable()).thenReturn(false);
        when(available.isAvailable()).thenReturn(true);
        when(available.query("hello")).thenReturn(CompletableFuture.completedFuture("answer"));
        when(available.index("hello")).thenReturn(CompletableFuture.completedFuture(null));
        when(available.getIndexMinLength()).thenReturn(128);

        RagProviderRegistry registry = new RagProviderRegistry();
        registry.replaceProviders("plugin-a", List.of(unavailable));
        registry.replaceProviders("plugin-b", List.of(available));
        PluginBackedRagPort port = new PluginBackedRagPort(registry);

        assertEquals("answer", port.query("hello").join());
        assertNull(port.index("hello").join());
        assertTrue(port.isAvailable());
        assertEquals(128, port.getIndexMinLength());
        verify(available).query("hello");
        verify(available).index("hello");
    }
}
