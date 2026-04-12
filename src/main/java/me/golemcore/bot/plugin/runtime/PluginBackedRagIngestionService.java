package me.golemcore.bot.plugin.runtime;

import lombok.RequiredArgsConstructor;
import me.golemcore.plugin.api.extension.model.rag.RagCorpusRef;
import me.golemcore.plugin.api.extension.model.rag.RagDocument;
import me.golemcore.plugin.api.extension.model.rag.RagIngestionResult;
import me.golemcore.plugin.api.extension.model.rag.RagIngestionStatus;
import me.golemcore.plugin.api.extension.spi.RagIngestionProvider;
import me.golemcore.plugin.api.runtime.RagIngestionService;
import me.golemcore.plugin.api.runtime.model.RagIngestionTargetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class PluginBackedRagIngestionService implements RagIngestionService {

    private final RagIngestionProviderRegistry ragIngestionProviderRegistry;

    @Override
    public List<RagIngestionTargetDescriptor> listInstalledTargets() {
        return ragIngestionProviderRegistry.listInstalledTargets();
    }

    @Override
    public CompletableFuture<RagIngestionResult> upsertDocuments(
            String providerId,
            RagCorpusRef corpus,
            List<RagDocument> documents) {
        return findProvider(providerId)
                .map(provider -> provider.upsertDocuments(corpus, documents))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        unavailableResult(providerId, documents.size())));
    }

    @Override
    public CompletableFuture<RagIngestionResult> deleteDocuments(
            String providerId,
            RagCorpusRef corpus,
            List<String> documentIds) {
        return findProvider(providerId)
                .map(provider -> provider.deleteDocuments(corpus, documentIds))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        unavailableResult(providerId, documentIds.size())));
    }

    @Override
    public CompletableFuture<RagIngestionResult> resetCorpus(String providerId, RagCorpusRef corpus) {
        return findProvider(providerId)
                .map(provider -> provider.resetCorpus(corpus))
                .orElseGet(() -> CompletableFuture.completedFuture(unavailableResult(providerId, 0)));
    }

    @Override
    public CompletableFuture<RagIngestionStatus> getStatus(String providerId, RagCorpusRef corpus) {
        return findProvider(providerId)
                .map(provider -> provider.getStatus(corpus))
                .orElseGet(() -> CompletableFuture.completedFuture(new RagIngestionStatus(
                        "unknown",
                        unavailableMessage(providerId),
                        0,
                        0,
                        0,
                        null)));
    }

    private java.util.Optional<RagIngestionProvider> findProvider(String providerId) {
        return ragIngestionProviderRegistry.findAvailableProvider(providerId);
    }

    private RagIngestionResult unavailableResult(String providerId, int rejectedDocuments) {
        return new RagIngestionResult("failed", 0, rejectedDocuments, null, unavailableMessage(providerId));
    }

    private String unavailableMessage(String providerId) {
        return "RAG ingestion provider not available: " + providerId;
    }
}
