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

    public TacticSearchService(
            TacticQueryExpansionService queryExpansionService,
            TacticBm25IndexService bm25IndexService,
            RuntimeConfigService runtimeConfigService,
            TacticIndexRebuildService tacticIndexRebuildService) {
        this.queryExpansionService = queryExpansionService;
        this.bm25IndexService = bm25IndexService;
        this.runtimeConfigService = runtimeConfigService;
        this.tacticIndexRebuildService = tacticIndexRebuildService;
    }

    TacticSearchService(
            TacticQueryExpansionService queryExpansionService,
            TacticBm25IndexService bm25IndexService,
            RuntimeConfigService runtimeConfigService) {
        this(queryExpansionService, bm25IndexService, runtimeConfigService, null);
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
        return bm25IndexService.search(query, DEFAULT_LIMIT).stream()
                .map(scoredDocument -> TacticSearchResult.builder()
                        .tacticId(scoredDocument.document().getTacticId())
                        .artifactStreamId(scoredDocument.document().getArtifactStreamId())
                        .artifactKey(scoredDocument.document().getArtifactKey())
                        .artifactType(scoredDocument.document().getArtifactType())
                        .title(scoredDocument.document().getTitle())
                        .aliases(scoredDocument.document().getAliases())
                        .promotionState(scoredDocument.document().getPromotionState())
                        .rolloutStage(scoredDocument.document().getRolloutStage())
                        .score(scoredDocument.score())
                        .updatedAt(scoredDocument.document().getUpdatedAt())
                        .explanation(TacticSearchExplanation.builder()
                                .bm25Score(scoredDocument.score())
                                .matchedQueryViews(query.getQueryViews())
                                .matchedTerms(scoredDocument.matchedTerms())
                                .eligible(isEligible(scoredDocument.document().getPromotionState(), query))
                                .gatingReason(isEligible(scoredDocument.document().getPromotionState(), query)
                                        ? null
                                        : "promotion state denied by default runtime gating")
                                .finalScore(scoredDocument.score())
                                .build())
                        .build())
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
        return Boolean.TRUE.equals(selfEvolvingConfig.getEnabled())
                && selfEvolvingConfig.getTactics() != null
                && Boolean.TRUE.equals(selfEvolvingConfig.getTactics().getEnabled());
    }

    private boolean isEligible(String promotionState, TacticSearchQuery query) {
        if (Boolean.TRUE.equals(query.getShadowMode())) {
            return !"reverted".equalsIgnoreCase(promotionState);
        }
        return "approved".equalsIgnoreCase(promotionState) || "active".equalsIgnoreCase(promotionState);
    }
}
