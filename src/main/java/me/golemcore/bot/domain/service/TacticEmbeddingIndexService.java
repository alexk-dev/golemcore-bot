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

import me.golemcore.bot.port.outbound.EmbeddingClientResolverPort;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vector index and query path for tactic embeddings.
 */
@Service
public class TacticEmbeddingIndexService {

    private static final String EMBEDDING_STATUS_INDEXED = "indexed";
    private static final String EMBEDDING_STATUS_FAILED = "failed";

    private final RuntimeConfigService runtimeConfigService;
    private final TacticRecordService tacticRecordService;
    private final TacticSearchDocumentAssembler documentAssembler;
    private final EmbeddingClientResolverPort embeddingClientResolver;
    private final TacticSearchMetricsService metricsService;
    private final TacticEmbeddingSqliteIndexStore indexStore;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public TacticEmbeddingIndexService(
            RuntimeConfigService runtimeConfigService,
            TacticRecordService tacticRecordService,
            TacticSearchDocumentAssembler documentAssembler,
            EmbeddingClientResolverPort embeddingClientResolver,
            TacticSearchMetricsService metricsService,
            TacticEmbeddingSqliteIndexStore indexStore) {
        this.runtimeConfigService = runtimeConfigService;
        this.tacticRecordService = tacticRecordService;
        this.documentAssembler = documentAssembler;
        this.embeddingClientResolver = embeddingClientResolver;
        this.metricsService = metricsService;
        this.indexStore = indexStore;
    }

    public List<TacticSearchResult> search(TacticSearchQuery query) {
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = searchConfig();
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config = embeddingsConfig(searchConfig);
        if (!isHybridMode(searchConfig) || !isProviderConfigured(config)) {
            metricsService.recordActiveMode("bm25", "embeddings disabled");
            return List.of();
        }
        if (shouldSkipVectorSearch(config)) {
            return List.of();
        }

        ensureIndexWarm(config);
        Snapshot current = snapshot();
        if (current.vectors().isEmpty()) {
            return List.of();
        }

        try {
            EmbeddingPort client = embeddingClientResolver.resolve(config.getProvider());
            EmbeddingPort.EmbeddingResponse response = client.embed(new EmbeddingPort.EmbeddingRequest(
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    config.getDimensions(),
                    config.getTimeoutMs(),
                    List.of(query.getRawQuery())));
            if (response.vectors().isEmpty()) {
                return List.of();
            }
            List<Double> queryVector = response.vectors().getFirst();
            metricsService.recordActiveMode("hybrid", null);
            return current.documents().values().stream()
                    .map(document -> vectorResult(document,
                            cosineSimilarity(queryVector, current.vectors().get(document.getTacticId()))))
                    .filter(result -> result.getScore() != null && result.getScore() > 0.0d)
                    .sorted(Comparator.comparing(TacticSearchResult::getScore).reversed())
                    .limit(5)
                    .toList();
        } catch (RuntimeException exception) {
            metricsService.recordQueryFailure(exception.getMessage());
            return List.of();
        }
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public void rebuildAll() {
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = searchConfig();
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config = embeddingsConfig(searchConfig);
        if (!isHybridMode(searchConfig) || !isProviderConfigured(config)) {
            return;
        }
        if (shouldSkipVectorSearch(config)) {
            snapshot.set(Snapshot.empty());
            return;
        }
        List<TacticIndexDocument> documents = tacticDocuments();
        if (documents.isEmpty()) {
            indexStore.replaceAll(config.getProvider(), config.getModel(), config.getDimensions(), List.of());
            snapshot.set(Snapshot.empty());
            return;
        }
        try {
            EmbeddingPort client = embeddingClientResolver.resolve(config.getProvider());
            EmbeddingPort.EmbeddingResponse response = client.embed(new EmbeddingPort.EmbeddingRequest(
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    config.getDimensions(),
                    config.getTimeoutMs(),
                    documents.stream().map(TacticIndexDocument::getSemanticText).toList()));
            if (response.vectors().size() != documents.size()) {
                throw new IllegalStateException("Embedding response size mismatch");
            }
            Map<String, TacticIndexDocument> documentMap = new LinkedHashMap<>();
            Map<String, List<Double>> vectorMap = new HashMap<>();
            for (int i = 0; i < documents.size(); i++) {
                TacticIndexDocument document = documents.get(i);
                document.setEmbeddingStatus(EMBEDDING_STATUS_INDEXED);
                documentMap.put(document.getTacticId(), document);
                vectorMap.put(document.getTacticId(), response.vectors().get(i));
            }
            indexStore.replaceAll(
                    config.getProvider(),
                    config.getModel(),
                    config.getDimensions(),
                    toStoreEntries(documentMap, vectorMap));
            tacticRecordService.updateEmbeddingStatuses(statusMap(documentMap.keySet(), EMBEDDING_STATUS_INDEXED));
            snapshot.set(new Snapshot(documentMap, vectorMap, Instant.now()));
        } catch (RuntimeException exception) {
            tacticRecordService.updateEmbeddingStatuses(statusMap(documents, EMBEDDING_STATUS_FAILED));
            snapshot.set(Snapshot.empty());
            metricsService.recordIndexFailure(exception.getMessage());
        }
    }

    private void ensureIndexWarm(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        if (!snapshot().vectors().isEmpty()) {
            return;
        }
        if (hydrateSnapshotFromStore(config)) {
            return;
        }
        rebuildAll();
    }

    private RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig() {
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = runtimeConfigService.getSelfEvolvingConfig()
                .getTactics()
                .getSearch();
        return searchConfig != null ? searchConfig : new RuntimeConfig.SelfEvolvingTacticSearchConfig();
    }

    private RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig(
            RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig) {
        return searchConfig.getEmbeddings() != null
                ? searchConfig.getEmbeddings()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
    }

    private boolean isHybridMode(RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig) {
        return searchConfig.getMode() != null && "hybrid".equalsIgnoreCase(searchConfig.getMode());
    }

    private boolean isProviderConfigured(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        return Boolean.TRUE.equals(config.getEnabled())
                && !StringValueSupport.isBlank(config.getProvider())
                && !StringValueSupport.isBlank(config.getBaseUrl())
                && !StringValueSupport.isBlank(config.getModel());
    }

    private boolean shouldSkipVectorSearch(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        TacticSearchMetricsService.Snapshot metricsSnapshot = metricsService.snapshot();
        if (!"ollama".equalsIgnoreCase(config.getProvider())) {
            return false;
        }
        if (!"bm25".equalsIgnoreCase(metricsSnapshot.activeMode())) {
            return false;
        }
        String reason = metricsSnapshot.lastReason();
        return reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("local embedding");
    }

    private boolean hydrateSnapshotFromStore(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        List<TacticIndexDocument> documents = tacticDocuments();
        if (documents.isEmpty()) {
            return false;
        }
        Map<String, TacticEmbeddingSqliteIndexStore.Entry> persistedEntries = indexStore.loadEntries(
                config.getProvider(),
                config.getModel());
        if (persistedEntries.size() != documents.size()) {
            return false;
        }
        Map<String, TacticIndexDocument> documentMap = new LinkedHashMap<>();
        Map<String, List<Double>> vectorMap = new HashMap<>();
        Instant updatedAt = Instant.EPOCH;
        for (TacticIndexDocument document : documents) {
            TacticEmbeddingSqliteIndexStore.Entry persistedEntry = persistedEntries.get(document.getTacticId());
            if (persistedEntry == null
                    || !Objects.equals(persistedEntry.contentRevisionId(), document.getContentRevisionId())
                    || !Objects.equals(persistedEntry.dimensions(), config.getDimensions())) {
                return false;
            }
            document.setEmbeddingStatus(EMBEDDING_STATUS_INDEXED);
            documentMap.put(document.getTacticId(), document);
            vectorMap.put(document.getTacticId(), persistedEntry.vector());
            if (persistedEntry.updatedAt() != null && persistedEntry.updatedAt().isAfter(updatedAt)) {
                updatedAt = persistedEntry.updatedAt();
            }
        }
        tacticRecordService.updateEmbeddingStatuses(statusMap(documentMap.keySet(), EMBEDDING_STATUS_INDEXED));
        snapshot.set(new Snapshot(documentMap, vectorMap, updatedAt));
        return true;
    }

    private List<TacticIndexDocument> tacticDocuments() {
        return tacticRecordService.getAll().stream()
                .map(documentAssembler::assemble)
                .toList();
    }

    private List<TacticEmbeddingSqliteIndexStore.Entry> toStoreEntries(
            Map<String, TacticIndexDocument> documentMap,
            Map<String, List<Double>> vectorMap) {
        return documentMap.values().stream()
                .map(document -> new TacticEmbeddingSqliteIndexStore.Entry(
                        document.getTacticId(),
                        document.getContentRevisionId(),
                        vectorMap.get(document.getTacticId()),
                        document.getUpdatedAt()))
                .toList();
    }

    private Map<String, String> statusMap(Iterable<String> tacticIds, String status) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (String tacticId : tacticIds) {
            if (!StringValueSupport.isBlank(tacticId)) {
                statuses.put(tacticId, status);
            }
        }
        return statuses;
    }

    private Map<String, String> statusMap(List<TacticIndexDocument> documents, String status) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (TacticIndexDocument document : documents) {
            if (document != null && !StringValueSupport.isBlank(document.getTacticId())) {
                statuses.put(document.getTacticId(), status);
            }
        }
        return statuses;
    }

    private TacticSearchResult vectorResult(TacticIndexDocument document, double similarity) {
        return TacticSearchResult.builder()
                .tacticId(document.getTacticId())
                .artifactStreamId(document.getArtifactStreamId())
                .originArtifactStreamId(document.getOriginArtifactStreamId())
                .artifactKey(document.getArtifactKey())
                .artifactType(document.getArtifactType())
                .title(document.getTitle())
                .aliases(document.getAliases())
                .contentRevisionId(document.getContentRevisionId())
                .intentSummary(document.getIntentSummary())
                .behaviorSummary(document.getBehaviorSummary())
                .toolSummary(document.getToolSummary())
                .outcomeSummary(document.getOutcomeSummary())
                .benchmarkSummary(document.getBenchmarkSummary())
                .approvalNotes(document.getApprovalNotes())
                .evidenceSnippets(document.getEvidenceSnippets())
                .taskFamilies(document.getTaskFamilies())
                .tags(document.getTags())
                .promotionState(document.getPromotionState())
                .rolloutStage(document.getRolloutStage())
                .score(similarity)
                .successRate(document.getSuccessRate())
                .benchmarkWinRate(document.getBenchmarkWinRate())
                .regressionFlags(document.getRegressionFlags())
                .recencyScore(document.getRecencyScore())
                .golemLocalUsageSuccess(document.getGolemLocalUsageSuccess())
                .embeddingStatus(document.getEmbeddingStatus())
                .updatedAt(document.getUpdatedAt())
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .vectorScore(similarity)
                        .finalScore(similarity)
                        .build())
                .build();
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftMagnitude = 0.0d;
        double rightMagnitude = 0.0d;
        for (int i = 0; i < left.size(); i++) {
            double leftValue = left.get(i);
            double rightValue = right.get(i);
            dot += leftValue * rightValue;
            leftMagnitude += leftValue * leftValue;
            rightMagnitude += rightValue * rightValue;
        }
        if (leftMagnitude == 0.0d || rightMagnitude == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }

    public record Snapshot(
            Map<String, TacticIndexDocument> documents,
            Map<String, List<Double>> vectors,
            Instant updatedAt) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Instant.EPOCH);
        }
    }
}
