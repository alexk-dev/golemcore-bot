package me.golemcore.bot.plugin.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.golemcore.plugin.api.extension.model.rag.RagIngestionCapabilities;
import me.golemcore.plugin.api.extension.spi.RagIngestionProvider;
import me.golemcore.plugin.api.runtime.model.RagIngestionTargetDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

class RagIngestionProviderRegistryTest {

    @Test
    void shouldListInstalledTargetsWithoutFilteringByAvailability() {
        RagIngestionProvider unavailable = mock(RagIngestionProvider.class);
        RagIngestionProvider available = mock(RagIngestionProvider.class);
        when(unavailable.getProviderId()).thenReturn("golemcore/lightrag");
        when(unavailable.isAvailable()).thenReturn(false);
        when(unavailable.getCapabilities()).thenReturn(new RagIngestionCapabilities(false, false, false, 32));
        when(available.getProviderId()).thenReturn("acme/raggy");
        when(available.isAvailable()).thenReturn(true);
        when(available.getCapabilities()).thenReturn(new RagIngestionCapabilities(true, true, true, 100));

        RagIngestionProviderRegistry registry = new RagIngestionProviderRegistry();
        registry.replaceProviders("golemcore/lightrag", List.of(unavailable));
        registry.replaceProviders("acme/raggy-plugin", List.of(available));

        assertEquals(List.of(
                new RagIngestionTargetDescriptor(
                        "acme/raggy",
                        "acme/raggy-plugin",
                        "acme/raggy",
                        new RagIngestionCapabilities(true, true, true, 100)),
                new RagIngestionTargetDescriptor(
                        "golemcore/lightrag",
                        "golemcore/lightrag",
                        "golemcore/lightrag",
                        new RagIngestionCapabilities(false, false, false, 32))),
                registry.listInstalledTargets());
    }

    @Test
    void shouldFindAvailableProviderByIdOnlyWhenEnabled() {
        RagIngestionProvider provider = mock(RagIngestionProvider.class);
        when(provider.getProviderId()).thenReturn("golemcore/lightrag");
        when(provider.isAvailable()).thenReturn(true, false);

        RagIngestionProviderRegistry registry = new RagIngestionProviderRegistry();
        registry.replaceProviders("golemcore/lightrag", List.of(provider));

        assertTrue(registry.findAvailableProvider("golemcore/lightrag").isPresent());
        assertFalse(registry.findAvailableProvider("golemcore/lightrag").isPresent());
    }
}
