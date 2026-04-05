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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates lexical tactic retrieval with default-safe promotion-state
 * gating.
 */
@Service
public class TacticSearchService {

    private static final int DEFAULT_LIMIT = 5;

    private final TacticQueryExpansionService queryExpansionService;
    private final TacticBm25IndexService bm25IndexService;
    private final RuntimeConfigService runtimeConfigService;
    private final TacticIndexRebuildService tacticIndexRebuildService;
    private final TacticEmbeddingIndexService tacticEmbeddingIndexService;
    private final TacticHybridRankingService tacticHybridRankingService;

    public TacticSearchService(
            TacticQueryExpansionService queryExpansionService,
            TacticBm25IndexService bm25IndexService,
            RuntimeConfigService runtimeConfigService,
            TacticIndexRebuildService tacticIndexRebuildService,
            TacticEmbeddingIndexService tacticEmbeddingIndexService,
            TacticHybridRankingService tacticHybridRankingService) {
        this.queryExpansionService = queryExpansionService;
        this.bm25IndexService = bm25IndexService;
        this.runtimeConfigService = runtimeConfigService;
        this.tacticIndexRebuildService = tacticIndexRebuildService;
        this.tacticEmbeddingIndexService = tacticEmbeddingIndexService;
        this.tacticHybridRankingService = tacticHybridRankingService;
    }

    public TacticSearchQuery buildQuery(AgentContext context) {
        return queryExpansionService.expand(context);
    }

    public List<TacticSearchResult> search(AgentContext context) {
        return search(buildQuery(context));
    }

    public List<TacticSearchResult> search(String query) {
        return search(queryExpansionService.expand(query));
    }

    public List<TacticSearchResult> search(TacticSearchQuery query) {
        if (!isSearchEnabled()) {
            return List.of();
        }
        ensureIndexWarm();
        List<TacticSearchResult> lexicalHits = bm25IndexService.search(query, DEFAULT_LIMIT).stream()
                .map(scoredDocument -> lexicalResult(query, scoredDocument))
                .filter(result -> Boolean.TRUE.equals(result.getExplanation().getEligible()))
                .toList();
        List<TacticSearchResult> vectorHits = tacticEmbeddingIndexService != null
                ? tacticEmbeddingIndexService.search(query).stream()
                        .filter(result -> isEligible(result.getPromotionState(), query))
                        .toList()
                : List.of();
        if (tacticHybridRankingService == null) {
            return lexicalHits;
        }
        return tacticHybridRankingService.rank(query, lexicalHits, vectorHits).stream()
                .map(result -> applyEligibility(result, query))
                .filter(result -> Boolean.TRUE.equals(result.getExplanation().getEligible()))
                .toList();
    }

    private void ensureIndexWarm() {
        if (!bm25IndexService.snapshot().documents().isEmpty() || tacticIndexRebuildService == null) {
            return;
        }
        tacticIndexRebuildService.rebuildAll();
    }

    private boolean isSearchEnabled() {
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigService.getSelfEvolvingConfig();
        if (selfEvolvingConfig == null || !Boolean.TRUE.equals(selfEvolvingConfig.getEnabled())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics();
        return tacticsConfig != null && Boolean.TRUE.equals(tacticsConfig.getEnabled());
    }

    private boolean isEligible(String promotionState, TacticSearchQuery query) {
        if (Boolean.TRUE.equals(query.getShadowMode())) {
            return !"reverted".equalsIgnoreCase(promotionState);
        }
        return "approved".equalsIgnoreCase(promotionState) || "active".equalsIgnoreCase(promotionState);
    }

    private TacticSearchResult lexicalResult(
            TacticSearchQuery query,
            TacticBm25IndexService.ScoredDocument scoredDocument) {
        boolean eligible = isEligible(scoredDocument.document().getPromotionState(), query);
        return TacticSearchResult.builder()
                .tacticId(scoredDocument.document().getTacticId())
                .artifactStreamId(scoredDocument.document().getArtifactStreamId())
                .originArtifactStreamId(scoredDocument.document().getOriginArtifactStreamId())
                .artifactKey(scoredDocument.document().getArtifactKey())
                .artifactType(scoredDocument.document().getArtifactType())
                .title(scoredDocument.document().getTitle())
                .aliases(scoredDocument.document().getAliases())
                .contentRevisionId(scoredDocument.document().getContentRevisionId())
                .intentSummary(scoredDocument.document().getIntentSummary())
                .behaviorSummary(scoredDocument.document().getBehaviorSummary())
                .toolSummary(scoredDocument.document().getToolSummary())
                .outcomeSummary(scoredDocument.document().getOutcomeSummary())
                .benchmarkSummary(scoredDocument.document().getBenchmarkSummary())
                .approvalNotes(scoredDocument.document().getApprovalNotes())
                .evidenceSnippets(scoredDocument.document().getEvidenceSnippets())
                .taskFamilies(scoredDocument.document().getTaskFamilies())
                .tags(scoredDocument.document().getTags())
                .promotionState(scoredDocument.document().getPromotionState())
                .rolloutStage(scoredDocument.document().getRolloutStage())
                .score(scoredDocument.score())
                .successRate(scoredDocument.document().getSuccessRate())
                .benchmarkWinRate(scoredDocument.document().getBenchmarkWinRate())
                .regressionFlags(scoredDocument.document().getRegressionFlags())
                .recencyScore(scoredDocument.document().getRecencyScore())
                .golemLocalUsageSuccess(scoredDocument.document().getGolemLocalUsageSuccess())
                .embeddingStatus(scoredDocument.document().getEmbeddingStatus())
                .updatedAt(scoredDocument.document().getUpdatedAt())
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("bm25")
                        .bm25Score(scoredDocument.score())
                        .matchedQueryViews(query.getQueryViews())
                        .matchedTerms(scoredDocument.matchedTerms())
                        .eligible(eligible)
                        .gatingReason(eligible ? null : "promotion state denied by default runtime gating")
                        .finalScore(scoredDocument.score())
                        .build())
                .build();
    }

    private TacticSearchResult applyEligibility(TacticSearchResult result, TacticSearchQuery query) {
        if (result == null) {
            return null;
        }
        boolean eligible = isEligible(result.getPromotionState(), query);
        TacticSearchExplanation explanation = result.getExplanation() != null
                ? result.getExplanation()
                : TacticSearchExplanation.builder().build();
        explanation.setEligible(eligible);
        explanation.setGatingReason(eligible ? null : "promotion state denied by default runtime gating");
        result.setExplanation(explanation);
        return result;
    }
}
