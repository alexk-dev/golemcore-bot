package me.golemcore.bot.domain.selfevolving.tactic;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.adapter.outbound.selfevolving.SqliteTacticEmbeddingIndexAdapter;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.port.outbound.EmbeddingClientResolverPort;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.StorageSettingsPort;
import me.golemcore.bot.port.outbound.selfevolving.TacticEmbeddingIndexPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TacticEmbeddingIndexServiceTest {

    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private TacticRecordService tacticRecordService;
    private EmbeddingClientResolverPort embeddingClientResolver;
    private EmbeddingPort embeddingPort;
    private TacticSearchMetricsService metricsService;
    private SqliteTacticEmbeddingIndexAdapter indexStore;
    private TacticEmbeddingIndexService service;

    @BeforeEach
    void setUp() throws Exception {
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        tacticRecordService = mock(TacticRecordService.class);
        embeddingClientResolver = mock(EmbeddingClientResolverPort.class);
        embeddingPort = mock(EmbeddingPort.class);
        metricsService = new TacticSearchMetricsService(Clock.fixed(
                Instant.parse("2026-04-02T00:00:00Z"),
                ZoneOffset.UTC));
        Path tempDir = Files.createTempDirectory("tactic-embedding-index-test");
        StorageSettingsPort storageSettingsPort = () -> new StorageSettingsPort.StorageSettings(tempDir.toString());
        indexStore = new SqliteTacticEmbeddingIndexAdapter(storageSettingsPort, new ObjectMapper());
        service = new TacticEmbeddingIndexService(
                runtimeConfigPort,
                tacticRecordService,
                new TacticSearchDocumentAssembler(),
                embeddingClientResolver,
                metricsService,
                indexStore);
    }

    @Test
    void shouldRebuildIndexAndReturnVectorResultsOrderedByCosineSimilarity() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));
        when(embeddingClientResolver.resolve("openai_compatible")).thenReturn(embeddingPort);
        when(embeddingPort.embed(any()))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d), List.of(0.2d, 0.98d))))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d))));

        List<TacticSearchResult> results = service.search(query());

        assertEquals(List.of("planner", "rollback"), results.stream().map(TacticSearchResult::getTacticId).toList());
        assertEquals("hybrid", results.getFirst().getExplanation().getSearchMode());
        assertTrue(results.getFirst().getScore() > results.get(1).getScore());
        assertEquals("hybrid", metricsService.snapshot().activeMode());
        verify(embeddingPort, times(2)).embed(any());
    }

    @Test
    void shouldRecordIndexFailureWhenEmbeddingResponseSizeMismatchesDocuments() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));
        when(embeddingClientResolver.resolve("openai_compatible")).thenReturn(embeddingPort);
        when(embeddingPort.embed(any())).thenReturn(new EmbeddingPort.EmbeddingResponse(
                "text-embedding-3-large",
                List.of(List.of(1.0d, 0.0d))));

        service.rebuildAll();

        assertTrue(service.snapshot().vectors().isEmpty());
        assertEquals(1L, metricsService.snapshot().degradedIndexFailureCount());
        assertEquals("Embedding response size mismatch", metricsService.snapshot().lastReason());
    }

    @Test
    void shouldRecordQueryFailureWhenQueryEmbeddingFailsAfterWarmIndex() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan")));
        when(embeddingClientResolver.resolve("openai_compatible")).thenReturn(embeddingPort);
        when(embeddingPort.embed(any()))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d))))
                .thenThrow(new IllegalStateException("embedding query failed"));

        List<TacticSearchResult> results = service.search(query());

        assertTrue(results.isEmpty());
        assertEquals(1L, metricsService.snapshot().degradedQueryFailureCount());
        assertEquals("embedding query failed", metricsService.snapshot().lastReason());
        assertEquals(1, service.snapshot().vectors().size());
    }

    @Test
    void shouldSkipOllamaVectorSearchWhenMetricsAlreadyDegradedToLocalBm25() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("ollama"));
        metricsService.recordActiveMode("bm25", "local embedding model unavailable");

        List<TacticSearchResult> results = service.search(query());

        assertTrue(results.isEmpty());
        verifyNoInteractions(embeddingClientResolver);
    }

    @Test
    void shouldReturnEmptyAndMarkBm25WhenEmbeddingsDisabled() {
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig
                .builder()
                .enabled(false)
                .provider("openai_compatible")
                .baseUrl("https://embeddings.example")
                .model("text-embedding-3-large")
                .build();
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(true)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(embeddingsConfig)
                                .build())
                        .build())
                .build());

        List<TacticSearchResult> results = service.search(query());

        assertTrue(results.isEmpty());
        assertEquals("bm25", metricsService.snapshot().activeMode());
        assertEquals("embeddings disabled", metricsService.snapshot().lastReason());
        verifyNoInteractions(embeddingClientResolver);
    }

    @Test
    void shouldReturnEmptyAndMarkBm25WhenTacticsSubsystemDisabled() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(false)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(true)
                                        .provider("openai_compatible")
                                        .baseUrl("https://embeddings.example")
                                        .model("text-embedding-3-large")
                                        .build())
                                .build())
                        .build())
                .build());

        List<TacticSearchResult> results = service.search(query());

        assertTrue(results.isEmpty());
        assertEquals("bm25", metricsService.snapshot().activeMode());
        assertEquals("selfevolving tactics disabled", metricsService.snapshot().lastReason());
        verifyNoInteractions(embeddingClientResolver);
    }

    @Test
    void shouldPersistVectorsToSqliteAndReloadWarmIndexWithoutReembeddingDocuments() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));
        when(embeddingClientResolver.resolve("openai_compatible")).thenReturn(embeddingPort);
        when(embeddingPort.embed(any()))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d), List.of(0.2d, 0.98d))))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d))));

        service.rebuildAll();

        assertTrue(indexStore.hasEntry("planner", "openai_compatible", "text-embedding-3-large"));
        verify(tacticRecordService).updateEmbeddingStatuses(eq(Map.of(
                "planner", "indexed",
                "rollback", "indexed")));

        TacticEmbeddingIndexService reloaded = new TacticEmbeddingIndexService(
                runtimeConfigPort,
                tacticRecordService,
                new TacticSearchDocumentAssembler(),
                embeddingClientResolver,
                metricsService,
                indexStore);

        List<TacticSearchResult> results = reloaded.search(query());

        assertEquals(List.of("planner", "rollback"), results.stream().map(TacticSearchResult::getTacticId).toList());
        assertEquals(2,
                indexStore.loadEntries("openai_compatible", "text-embedding-3-large").get("planner").dimensions());
        verify(embeddingPort, times(2)).embed(any());
    }

    @Test
    void shouldReportMissingPersistedEntriesForCurrentTacticDocuments() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));

        indexStore.replaceAll(
                "openai_compatible",
                "text-embedding-3-large",
                2,
                List.of(new TacticEmbeddingIndexPort.Entry(
                        "planner",
                        "rev-planner",
                        List.of(1.0d, 0.0d),
                        Instant.parse("2026-04-04T19:10:00Z"))));

        assertEquals(List.of("rollback"), service.findMissingPersistedEntryTacticIds());
    }

    @Test
    void shouldTreatMismatchedRevisionOrDimensionsAsMissingPersistedEntries() {
        when(runtimeConfigPort.getSelfEvolvingConfig())
                .thenReturn(hybridConfigWithDimensions("openai_compatible", 3));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));

        indexStore.replaceAll(
                "openai_compatible",
                "text-embedding-3-large",
                2,
                List.of(
                        new TacticEmbeddingIndexPort.Entry(
                                "planner",
                                "stale-revision",
                                List.of(1.0d, 0.0d),
                                Instant.parse("2026-04-04T19:10:00Z")),
                        new TacticEmbeddingIndexPort.Entry(
                                "rollback",
                                "rev-rollback",
                                List.of(0.2d, 0.98d),
                                Instant.parse("2026-04-04T19:11:00Z"))));

        assertEquals(List.of("planner", "rollback"), service.findMissingPersistedEntryTacticIds());
    }

    @Test
    void shouldReturnNoMissingPersistedEntriesWhenStoreMatchesCurrentDocuments() {
        when(runtimeConfigPort.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));

        indexStore.replaceAll(
                "openai_compatible",
                "text-embedding-3-large",
                2,
                List.of(
                        new TacticEmbeddingIndexPort.Entry(
                                "planner",
                                "rev-planner",
                                List.of(1.0d, 0.0d),
                                Instant.parse("2026-04-04T19:10:00Z")),
                        new TacticEmbeddingIndexPort.Entry(
                                "rollback",
                                "rev-rollback",
                                List.of(0.2d, 0.98d),
                                Instant.parse("2026-04-04T19:11:00Z"))));

        assertTrue(service.findMissingPersistedEntryTacticIds().isEmpty());
    }

    @Test
    void shouldRebuildPersistedIndexWhenStoredDimensionsDoNotMatchConfig() {
        when(runtimeConfigPort.getSelfEvolvingConfig())
                .thenReturn(hybridConfigWithDimensions("openai_compatible", 3));
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("planner", "active", "Recover with an ordered shell plan"),
                tactic("rollback", "approved", "Rollback the last broken shell step")));
        when(embeddingClientResolver.resolve("openai_compatible")).thenReturn(embeddingPort);

        indexStore.replaceAll(
                "openai_compatible",
                "text-embedding-3-large",
                2,
                List.of(
                        new TacticEmbeddingIndexPort.Entry(
                                "planner",
                                "rev-planner",
                                List.of(1.0d, 0.0d),
                                Instant.parse("2026-04-04T19:10:00Z")),
                        new TacticEmbeddingIndexPort.Entry(
                                "rollback",
                                "rev-rollback",
                                List.of(0.2d, 0.98d),
                                Instant.parse("2026-04-04T19:11:00Z"))));

        when(embeddingPort.embed(any()))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d, 0.0d), List.of(0.2d, 0.98d, 0.0d))))
                .thenReturn(new EmbeddingPort.EmbeddingResponse(
                        "text-embedding-3-large",
                        List.of(List.of(1.0d, 0.0d, 0.0d))));

        List<TacticSearchResult> results = service.search(query());

        assertEquals(List.of("planner", "rollback"), results.stream().map(TacticSearchResult::getTacticId).toList());
        assertEquals(3,
                indexStore.loadEntries("openai_compatible", "text-embedding-3-large").get("planner").dimensions());
        verify(embeddingPort, times(2)).embed(any());
    }

    private RuntimeConfig.SelfEvolvingConfig hybridConfig(String provider) {
        return hybridConfigWithDimensions(provider, 2);
    }

    private RuntimeConfig.SelfEvolvingConfig hybridConfigWithDimensions(String provider, Integer dimensions) {
        return RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(true)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(true)
                                        .provider(provider)
                                        .baseUrl("https://embeddings.example")
                                        .apiKey(Secret.of("test-key"))
                                        .model("text-embedding-3-large")
                                        .dimensions(dimensions)
                                        .timeoutMs(5000)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private TacticSearchQuery query() {
        return TacticSearchQuery.builder()
                .rawQuery("recover failed shell command")
                .queryViews(List.of("recover", "shell", "failure"))
                .build();
    }

    private TacticRecord tactic(String tacticId, String promotionState, String behaviorSummary) {
        return TacticRecord.builder()
                .tacticId(tacticId)
                .artifactStreamId("stream-" + tacticId)
                .artifactKey("skill:" + tacticId)
                .artifactType("skill")
                .title(tacticId)
                .aliases(List.of(tacticId))
                .contentRevisionId("rev-" + tacticId)
                .intentSummary(behaviorSummary)
                .behaviorSummary(behaviorSummary)
                .toolSummary("shell git")
                .outcomeSummary("Stable recovery path")
                .benchmarkSummary("Wins recovery suite")
                .approvalNotes("Approved")
                .evidenceSnippets(List.of("trace:" + tacticId))
                .taskFamilies(List.of("recovery"))
                .tags(List.of("core"))
                .promotionState(promotionState)
                .rolloutStage(promotionState)
                .successRate(0.9d)
                .benchmarkWinRate(0.75d)
                .regressionFlags(List.of())
                .recencyScore(0.8d)
                .golemLocalUsageSuccess(0.85d)
                .embeddingStatus("indexed")
                .updatedAt(Instant.parse("2026-04-01T23:30:00Z"))
                .build();
    }
}
