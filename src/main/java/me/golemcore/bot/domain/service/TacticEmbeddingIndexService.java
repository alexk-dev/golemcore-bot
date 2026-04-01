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

import me.golemcore.bot.adapter.outbound.embedding.EmbeddingClientFactory;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vector index and query path for tactic embeddings.
 */
@Service
public class TacticEmbeddingIndexService {

    private final RuntimeConfigService runtimeConfigService;
    private final TacticRecordService tacticRecordService;
    private final TacticSearchDocumentAssembler documentAssembler;
    private final EmbeddingClientFactory embeddingClientFactory;
    private final TacticSearchMetricsService metricsService;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public TacticEmbeddingIndexService(
            RuntimeConfigService runtimeConfigService,
            TacticRecordService tacticRecordService,
            TacticSearchDocumentAssembler documentAssembler,
            EmbeddingClientFactory embeddingClientFactory,
            TacticSearchMetricsService metricsService) {
        this.runtimeConfigService = runtimeConfigService;
        this.tacticRecordService = tacticRecordService;
        this.documentAssembler = documentAssembler;
        this.embeddingClientFactory = embeddingClientFactory;
        this.metricsService = metricsService;
    }

    public List<TacticSearchResult> search(TacticSearchQuery query) {
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config = embeddingsConfig();
        if (!Boolean.TRUE.equals(config.getEnabled()) || !isProviderConfigured(config)) {
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
            EmbeddingPort client = embeddingClientFactory.resolve(config.getProvider());
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
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config = embeddingsConfig();
        if (!Boolean.TRUE.equals(config.getEnabled()) || !isProviderConfigured(config)) {
            return;
        }
        if (shouldSkipVectorSearch(config)) {
            snapshot.set(Snapshot.empty());
            return;
        }
        List<TacticIndexDocument> documents = tacticRecordService.getAll().stream()
                .map(documentAssembler::assemble)
                .toList();
        if (documents.isEmpty()) {
            snapshot.set(Snapshot.empty());
            return;
        }
        try {
            EmbeddingPort client = embeddingClientFactory.resolve(config.getProvider());
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
                documentMap.put(document.getTacticId(), document);
                vectorMap.put(document.getTacticId(), response.vectors().get(i));
            }
            snapshot.set(new Snapshot(documentMap, vectorMap, Instant.now()));
        } catch (RuntimeException exception) {
            metricsService.recordIndexFailure(exception.getMessage());
        }
    }

    private void ensureIndexWarm(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        if (!snapshot().vectors().isEmpty()) {
            return;
        }
        rebuildAll();
    }

    private RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig() {
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = runtimeConfigService.getSelfEvolvingConfig()
                .getTactics()
                .getSearch();
        return searchConfig != null && searchConfig.getEmbeddings() != null
                ? searchConfig.getEmbeddings()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
    }

    private boolean isProviderConfigured(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig config) {
        return !StringValueSupport.isBlank(config.getProvider())
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
