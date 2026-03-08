package me.golemcore.bot.plugin.runtime;

import me.golemcore.plugin.api.extension.spi.TtsProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic registry for TTS providers contributed by plugins.
 */
@Component
public class TtsProviderRegistry {

    private final Map<String, Map<String, TtsProvider>> providersByPlugin = new LinkedHashMap<>();

    public synchronized void replaceProviders(String pluginId, Collection<TtsProvider> providers) {
        Map<String, TtsProvider> normalized = new LinkedHashMap<>();
        for (TtsProvider provider : providers) {
            normalized.put(provider.getProviderId(), provider);
        }
        providersByPlugin.put(pluginId, normalized);
    }

    public synchronized void removeProviders(String pluginId) {
        providersByPlugin.remove(pluginId);
    }

    public synchronized Optional<TtsProvider> find(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        String normalizedId = providerId.trim().toLowerCase(java.util.Locale.ROOT);
        return providersByPlugin.values().stream()
                .flatMap(map -> map.values().stream())
                .filter(provider -> provider.getProviderId().equalsIgnoreCase(normalizedId)
                        || provider.getAliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(normalizedId)))
                .findFirst();
    }

    public synchronized Map<String, String> listProviderIds() {
        Map<String, String> result = new LinkedHashMap<>();
        providersByPlugin.values().forEach(providers -> providers.values()
                .forEach(provider -> result.put(provider.getProviderId(), provider.getProviderId())));
        return result;
    }
}
