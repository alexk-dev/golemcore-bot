package me.golemcore.bot.plugin.runtime;

import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.plugin.api.extension.spi.RagProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Host RAG port backed by dynamically loaded plugin providers.
 */
@Component
@Primary
public class PluginBackedRagPort implements RagPort {

    private final RagProviderRegistry ragProviderRegistry;

    public PluginBackedRagPort(RagProviderRegistry ragProviderRegistry) {
        this.ragProviderRegistry = ragProviderRegistry;
    }

    @Override
    public CompletableFuture<String> query(String query) {
        Optional<RagProvider> provider = ragProviderRegistry.findFirstAvailable();
        return provider.map(ragProvider -> ragProvider.query(query))
                .orElseGet(() -> CompletableFuture.completedFuture(""));
    }

    @Override
    public CompletableFuture<Void> index(String content) {
        Optional<RagProvider> provider = ragProviderRegistry.findFirstAvailable();
        return provider.map(ragProvider -> ragProvider.index(content))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    @Override
    public boolean isAvailable() {
        return ragProviderRegistry.findFirstAvailable().isPresent();
    }

    @Override
    public int getIndexMinLength() {
        return ragProviderRegistry.findFirstAvailable()
                .map(RagProvider::getIndexMinLength)
                .orElse(RagPort.super.getIndexMinLength());
    }
}
