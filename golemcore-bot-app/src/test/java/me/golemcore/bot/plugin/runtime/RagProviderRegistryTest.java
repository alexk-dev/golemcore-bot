package me.golemcore.bot.plugin.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.golemcore.plugin.api.extension.spi.RagProvider;
import me.golemcore.plugin.api.runtime.model.RagProviderDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

class RagProviderRegistryTest {

    @Test
    void shouldListInstalledProvidersWithoutFilteringByAvailability() {
        RagProvider unavailable = mock(RagProvider.class);
        RagProvider available = mock(RagProvider.class);
        when(unavailable.getProviderId()).thenReturn("golemcore/lightrag");
        when(unavailable.isAvailable()).thenReturn(false);
        when(available.getProviderId()).thenReturn("acme/raggy");
        when(available.isAvailable()).thenReturn(true);

        RagProviderRegistry registry = new RagProviderRegistry();
        registry.replaceProviders("golemcore/lightrag", List.of(unavailable));
        registry.replaceProviders("acme/raggy-plugin", List.of(available));

        assertEquals(List.of(
                new RagProviderDescriptor("acme/raggy", "acme/raggy-plugin", "acme/raggy"),
                new RagProviderDescriptor("golemcore/lightrag", "golemcore/lightrag", "golemcore/lightrag")),
                registry.listInstalledProviders());
    }
}
