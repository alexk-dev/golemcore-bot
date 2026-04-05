package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import me.golemcore.bot.port.outbound.EmbeddingClientResolverPort;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TacticEmbeddingIndexServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private TacticRecordService tacticRecordService;
    private EmbeddingClientResolverPort embeddingClientResolver;
    private EmbeddingPort embeddingPort;
    private TacticSearchMetricsService metricsService;
    private TacticEmbeddingSqliteIndexStore indexStore;
    private TacticEmbeddingIndexService service;

    @BeforeEach
    void setUp() throws Exception {
        runtimeConfigService = mock(RuntimeConfigService.class);
        tacticRecordService = mock(TacticRecordService.class);
        embeddingClientResolver = mock(EmbeddingClientResolverPort.class);
        embeddingPort = mock(EmbeddingPort.class);
        metricsService = new TacticSearchMetricsService(Clock.fixed(
                Instant.parse("2026-04-02T00:00:00Z"),
                ZoneOffset.UTC));
        BotProperties properties = new BotProperties();
        Path tempDir = Files.createTempDirectory("tactic-embedding-index-test");
        properties.getStorage().getLocal().setBasePath(tempDir.toString());
        indexStore = new TacticEmbeddingSqliteIndexStore(properties, new ObjectMapper());
        service = new TacticEmbeddingIndexService(
                runtimeConfigService,
                tacticRecordService,
                new TacticSearchDocumentAssembler(),
                embeddingClientResolver,
                metricsService,
                indexStore);
    }

    @Test
    void shouldRebuildIndexAndReturnVectorResultsOrderedByCosineSimilarity() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
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
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
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
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
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
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(hybridConfig("ollama"));
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
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(RuntimeConfig.SelfEvolvingConfig.builder()
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
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(RuntimeConfig.SelfEvolvingConfig.builder()
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
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(hybridConfig("openai_compatible"));
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
        verify(tacticRecordService).updateEmbeddingStatuses(eq(java.util.Map.of(
                "planner", "indexed",
                "rollback", "indexed")));

        TacticEmbeddingIndexService reloaded = new TacticEmbeddingIndexService(
                runtimeConfigService,
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
    void shouldRebuildPersistedIndexWhenStoredDimensionsDoNotMatchConfig() {
        when(runtimeConfigService.getSelfEvolvingConfig())
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
                        new TacticEmbeddingSqliteIndexStore.Entry(
                                "planner",
                                "rev-planner",
                                List.of(1.0d, 0.0d),
                                Instant.parse("2026-04-04T19:10:00Z")),
                        new TacticEmbeddingSqliteIndexStore.Entry(
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
                                        .apiKey("test-key")
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
