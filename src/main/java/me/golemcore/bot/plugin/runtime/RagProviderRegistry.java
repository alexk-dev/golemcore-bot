package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.RagProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic registry for RAG providers contributed by plugins.
 */
@Component
public class RagProviderRegistry {

    private final Map<String, Map<String, RagProvider>> providersByPlugin = new LinkedHashMap<>();

    public synchronized void replaceProviders(String pluginId, Collection<RagProvider> providers) {
        Map<String, RagProvider> normalized = new LinkedHashMap<>();
        for (RagProvider provider : providers) {
            normalized.put(provider.getProviderId(), provider);
        }
        providersByPlugin.put(pluginId, normalized);
    }

    public synchronized void removeProviders(String pluginId) {
        providersByPlugin.remove(pluginId);
    }

    public synchronized Optional<RagProvider> findFirstAvailable() {
        return providersByPlugin.values().stream()
                .flatMap(map -> map.values().stream())
                .filter(RagProvider::isAvailable)
                .findFirst();
    }
}
