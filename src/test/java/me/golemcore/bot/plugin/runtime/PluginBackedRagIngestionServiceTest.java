package me.golemcore.bot.plugin.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.plugin.api.extension.model.rag.RagCorpusRef;
import me.golemcore.plugin.api.extension.model.rag.RagDocument;
import me.golemcore.plugin.api.extension.model.rag.RagIngestionCapabilities;
import me.golemcore.plugin.api.extension.model.rag.RagIngestionResult;
import me.golemcore.plugin.api.extension.model.rag.RagIngestionStatus;
import me.golemcore.plugin.api.extension.spi.RagIngestionProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class PluginBackedRagIngestionServiceTest {

    private static final RagCorpusRef CORPUS = new RagCorpusRef("notion-workspace", "Notion Workspace");
    private static final List<RagDocument> DOCUMENTS = List.of(
            new RagDocument(
                    "page-1",
                    "Alpha",
                    "Root/Alpha",
                    "# Alpha",
                    "https://notion.so/page-1",
                    Map.of("source", "notion")));

    @Test
    void shouldReturnFallbackValuesWhenProviderIsMissing() {
        PluginBackedRagIngestionService service = new PluginBackedRagIngestionService(
                new RagIngestionProviderRegistry());

        assertEquals(
                new RagIngestionResult("failed", 0, 1, null, "RAG ingestion provider not available: missing"),
                service.upsertDocuments("missing", CORPUS, DOCUMENTS).join());
        assertEquals(
                new RagIngestionResult("failed", 0, 1, null, "RAG ingestion provider not available: missing"),
                service.deleteDocuments("missing", CORPUS, List.of("page-1")).join());
        assertEquals(
                new RagIngestionResult("failed", 0, 0, null, "RAG ingestion provider not available: missing"),
                service.resetCorpus("missing", CORPUS).join());
        assertEquals(
                new RagIngestionStatus("unknown", "RAG ingestion provider not available: missing", 0, 0, 0, null),
                service.getStatus("missing", CORPUS).join());
    }

    @Test
    void shouldDelegateToAvailableProvider() {
        RagIngestionProvider provider = mock(RagIngestionProvider.class);
        RagIngestionResult result = new RagIngestionResult("accepted", 1, 0, "job-1", "queued");
        RagIngestionStatus status = new RagIngestionStatus("running", "queued", 0, 1, 0, "2026-03-30T19:00:00Z");
        when(provider.getProviderId()).thenReturn("golemcore/lightrag");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.getCapabilities()).thenReturn(new RagIngestionCapabilities(false, false, false, 32));
        when(provider.upsertDocuments(CORPUS, DOCUMENTS)).thenReturn(CompletableFuture.completedFuture(result));
        when(provider.deleteDocuments(CORPUS, List.of("page-1")))
                .thenReturn(CompletableFuture.completedFuture(result));
        when(provider.resetCorpus(CORPUS)).thenReturn(CompletableFuture.completedFuture(result));
        when(provider.getStatus(CORPUS)).thenReturn(CompletableFuture.completedFuture(status));

        RagIngestionProviderRegistry registry = new RagIngestionProviderRegistry();
        registry.replaceProviders("golemcore/lightrag", List.of(provider));
        PluginBackedRagIngestionService service = new PluginBackedRagIngestionService(registry);

        assertEquals(result, service.upsertDocuments("golemcore/lightrag", CORPUS, DOCUMENTS).join());
        assertEquals(result, service.deleteDocuments("golemcore/lightrag", CORPUS, List.of("page-1")).join());
        assertEquals(result, service.resetCorpus("golemcore/lightrag", CORPUS).join());
        assertEquals(status, service.getStatus("golemcore/lightrag", CORPUS).join());
        verify(provider).upsertDocuments(CORPUS, DOCUMENTS);
        verify(provider).deleteDocuments(CORPUS, List.of("page-1"));
        verify(provider).resetCorpus(CORPUS);
        verify(provider).getStatus(CORPUS);
    }
}
