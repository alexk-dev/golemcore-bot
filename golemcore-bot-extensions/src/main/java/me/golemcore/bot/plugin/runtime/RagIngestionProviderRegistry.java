package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.RagIngestionProvider;
import me.golemcore.plugin.api.runtime.model.RagIngestionTargetDescriptor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic registry for RAG ingestion providers contributed by plugins.
 */
@Component
public class RagIngestionProviderRegistry {

    private final Map<String, Map<String, RagIngestionProvider>> providersByPlugin = new LinkedHashMap<>();

    public synchronized void replaceProviders(String pluginId, Collection<RagIngestionProvider> providers) {
        Map<String, RagIngestionProvider> normalized = new LinkedHashMap<>();
        for (RagIngestionProvider provider : providers) {
            normalized.put(provider.getProviderId(), provider);
        }
        providersByPlugin.put(pluginId, normalized);
    }

    public synchronized void removeProviders(String pluginId) {
        providersByPlugin.remove(pluginId);
    }

    public synchronized Optional<RagIngestionProvider> findAvailableProvider(String providerId) {
        return providersByPlugin.values().stream()
                .flatMap(map -> map.values().stream())
                .filter(provider -> provider.getProviderId().equals(providerId))
                .filter(RagIngestionProvider::isAvailable)
                .findFirst();
    }

    public synchronized List<RagIngestionTargetDescriptor> listInstalledTargets() {
        return providersByPlugin.entrySet().stream()
                .flatMap(entry -> entry.getValue().values().stream()
                        .map(provider -> new RagIngestionTargetDescriptor(
                                provider.getProviderId(),
                                entry.getKey(),
                                provider.getProviderId(),
                                provider.getCapabilities())))
                .sorted((left, right) -> left.providerId().compareTo(right.providerId()))
                .toList();
    }
}
