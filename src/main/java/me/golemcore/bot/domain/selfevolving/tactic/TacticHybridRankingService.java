package me.golemcore.bot.domain.selfevolving.tactic;

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

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Hybrid tactic ranking with RRF, quality priors, diversity, penalties, and a
 * tier-resolved reranker verdict.
 */
@Service
public class TacticHybridRankingService {

    private static final double RRF_K = 60.0d;
    private static final double RRF_SCALE = 15.0d;

    private static final double QUALITY_WEIGHT_SUCCESS = 0.35d;
    private static final double QUALITY_WEIGHT_BENCHMARK = 0.25d;
    private static final double QUALITY_WEIGHT_RECENCY = 0.15d;
    private static final double QUALITY_WEIGHT_GOLEM_LOCAL = 0.15d;
    private static final double QUALITY_DEFAULT_SIGNAL = 0.3d;

    private static final double PROMOTION_BOOST_ACTIVE = 0.12d;
    private static final double PROMOTION_BOOST_APPROVED = 0.08d;
    private static final double PROMOTION_BOOST_CANDIDATE = -0.05d;
    private static final double PROMOTION_BOOST_REVERTED = -0.20d;

    private static final double REGRESSION_PENALTY_PER_FLAG = 0.08d;
    private static final double REGRESSION_PENALTY_CAP = 0.25d;

    private static final double PERSONALIZATION_PER_TOOL = 0.04d;
    private static final double PERSONALIZATION_CAP = 0.12d;

    private static final double DIVERSITY_ARTIFACT_KEY_PENALTY = 0.05d;
    private static final double DIVERSITY_OVERLAP_WEIGHT = 0.04d;

    private final TacticSearchMetricsService metricsService;

    public TacticHybridRankingService(TacticSearchMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public List<TacticSearchResult> rank(
            TacticSearchQuery query,
            List<TacticSearchResult> lexicalHits,
            List<TacticSearchResult> vectorHits) {
        Map<String, Integer> lexicalRanks = rankMap(lexicalHits);
        Map<String, Integer> vectorRanks = rankMap(vectorHits);
        Map<String, TacticSearchResult> merged = mergeCandidates(lexicalHits, vectorHits);

        List<TacticSearchResult> prelim = merged.values().stream()
                .map(candidate -> decorateCandidate(query, candidate, lexicalRanks, vectorRanks))
                .sorted(Comparator.comparing(TacticSearchResult::getScore).reversed())
                .toList();

        String searchMode = vectorRanks.isEmpty() ? "bm25" : "hybrid";
        List<TacticSearchResult> diversified = applyMmr(prelim);
        metricsService.recordActiveMode(searchMode, null);
        return diversified;
    }

    private Map<String, Integer> rankMap(List<TacticSearchResult> hits) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (hits == null) {
            return map;
        }
        for (int index = 0; index < hits.size(); index++) {
            map.put(hits.get(index).getTacticId(), index + 1);
        }
        return map;
    }

    private Map<String, TacticSearchResult> mergeCandidates(
            List<TacticSearchResult> lexicalHits,
            List<TacticSearchResult> vectorHits) {
        Map<String, TacticSearchResult> merged = new LinkedHashMap<>();
        for (TacticSearchResult hit : concat(lexicalHits, vectorHits)) {
            if (hit == null || StringValueSupport.isBlank(hit.getTacticId())) {
                continue;
            }
            merged.merge(hit.getTacticId(), hit, this::preferRicherCandidate);
        }
        return merged;
    }

    private TacticSearchResult decorateCandidate(
            TacticSearchQuery query,
            TacticSearchResult candidate,
            Map<String, Integer> lexicalRanks,
            Map<String, Integer> vectorRanks) {
        double bm25Score = rrf(lexicalRanks.get(candidate.getTacticId()));
        double vectorScore = rrf(vectorRanks.get(candidate.getTacticId()));
        double rrfScore = bm25Score + vectorScore;
        double qualityPrior = qualityPrior(candidate);
        double negativeMemoryPenalty = negativeMemoryPenalty(candidate);
        double personalizationBoost = personalizationBoost(query, candidate);
        double finalScore = rrfScore + qualityPrior + personalizationBoost - negativeMemoryPenalty;
        String searchMode = vectorRanks.isEmpty() ? "bm25" : "hybrid";

        TacticSearchExplanation explanation = candidate.getExplanation() != null
                ? candidate.getExplanation()
                : TacticSearchExplanation.builder().build();
        explanation.setSearchMode(searchMode);
        explanation.setBm25Score(bm25Score);
        explanation.setVectorScore(vectorScore);
        explanation.setRrfScore(rrfScore);
        explanation.setQualityPrior(qualityPrior);
        explanation.setNegativeMemoryPenalty(negativeMemoryPenalty);
        explanation.setPersonalizationBoost(personalizationBoost);
        explanation.setFinalScore(finalScore);
        explanation.setMatchedQueryViews(query.getQueryViews());
        explanation.setEligible(true);

        TacticSearchResult copy = copy(candidate);
        copy.setScore(finalScore);
        copy.setExplanation(explanation);
        return copy;
    }

    private List<TacticSearchResult> applyMmr(List<TacticSearchResult> ranked) {
        List<TacticSearchResult> selected = new ArrayList<>();
        List<TacticSearchResult> remaining = new ArrayList<>(ranked);
        while (!remaining.isEmpty()) {
            TacticSearchResult best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (TacticSearchResult candidate : remaining) {
                double penalty = diversityPenalty(candidate, selected);
                double score = candidate.getScore() - penalty;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            TacticSearchExplanation explanation = best.getExplanation();
            explanation.setMmrDiversityAdjustment(-(best.getScore() - bestScore));
            explanation.setFinalScore(bestScore);
            best.setScore(bestScore);
            selected.add(best);
            remaining.remove(best);
        }
        selected.sort(Comparator.comparing(TacticSearchResult::getScore).reversed());
        return selected;
    }

    private double rrf(Integer rank) {
        return rank == null ? 0.0d : RRF_SCALE / (RRF_K + rank);
    }

    private double qualityPrior(TacticSearchResult candidate) {
        double weightedSum = 0.0d;
        double weightTotal = 0.0d;
        if (candidate.getSuccessRate() != null) {
            weightedSum += candidate.getSuccessRate() * QUALITY_WEIGHT_SUCCESS;
            weightTotal += QUALITY_WEIGHT_SUCCESS;
        }
        if (candidate.getBenchmarkWinRate() != null) {
            weightedSum += candidate.getBenchmarkWinRate() * QUALITY_WEIGHT_BENCHMARK;
            weightTotal += QUALITY_WEIGHT_BENCHMARK;
        }
        if (candidate.getRecencyScore() != null) {
            weightedSum += candidate.getRecencyScore() * QUALITY_WEIGHT_RECENCY;
            weightTotal += QUALITY_WEIGHT_RECENCY;
        }
        if (candidate.getGolemLocalUsageSuccess() != null) {
            weightedSum += candidate.getGolemLocalUsageSuccess() * QUALITY_WEIGHT_GOLEM_LOCAL;
            weightTotal += QUALITY_WEIGHT_GOLEM_LOCAL;
        }
        // When no signals have been observed yet, use a pessimistic prior
        // rather than 0.5: a brand-new tactic should not outrank tactics with
        // at least one recorded measurement just because nothing is known.
        double signalScore = weightTotal > 0.0d ? weightedSum / weightTotal : QUALITY_DEFAULT_SIGNAL;
        double promotionBoost = switch (normalize(candidate.getPromotionState())) {
        case "active" -> PROMOTION_BOOST_ACTIVE;
        case "approved" -> PROMOTION_BOOST_APPROVED;
        case "candidate" -> PROMOTION_BOOST_CANDIDATE;
        case "reverted" -> PROMOTION_BOOST_REVERTED;
        default -> 0.0d;
        };
        return signalScore + promotionBoost;
    }

    private double negativeMemoryPenalty(TacticSearchResult candidate) {
        int regressionCount = candidate.getRegressionFlags() != null ? candidate.getRegressionFlags().size() : 0;
        return Math.min(REGRESSION_PENALTY_CAP, regressionCount * REGRESSION_PENALTY_PER_FLAG);
    }

    private double personalizationBoost(TacticSearchQuery query, TacticSearchResult candidate) {
        if (query.getAvailableTools() == null || query.getAvailableTools().isEmpty()) {
            return 0.0d;
        }
        String toolSummary = normalize(candidate.getToolSummary());
        double boost = 0.0d;
        for (String tool : query.getAvailableTools()) {
            if (!StringValueSupport.isBlank(tool) && toolSummary.contains(normalize(tool))) {
                boost += PERSONALIZATION_PER_TOOL;
            }
        }
        return Math.min(PERSONALIZATION_CAP, boost);
    }

    private double diversityPenalty(TacticSearchResult candidate, List<TacticSearchResult> selected) {
        double penalty = 0.0d;
        for (TacticSearchResult existing : selected) {
            if (normalize(candidate.getArtifactKey()).equals(normalize(existing.getArtifactKey()))) {
                penalty = Math.max(penalty, DIVERSITY_ARTIFACT_KEY_PENALTY);
            }
            penalty = Math.max(penalty, overlapPenalty(candidate, existing));
        }
        return penalty;
    }

    private double overlapPenalty(TacticSearchResult left, TacticSearchResult right) {
        Set<String> leftTokens = tokens(
                left.getTitle() + " " + left.getBehaviorSummary() + " " + left.getToolSummary());
        Set<String> rightTokens = tokens(
                right.getTitle() + " " + right.getBehaviorSummary() + " " + right.getToolSummary());
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0d;
        }
        int intersection = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        return DIVERSITY_OVERLAP_WEIGHT
                * ((double) intersection / Math.max(1, Math.min(leftTokens.size(), rightTokens.size())));
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (StringValueSupport.isBlank(value)) {
            return tokens;
        }
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9:_-]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private TacticSearchResult preferRicherCandidate(TacticSearchResult left, TacticSearchResult right) {
        int leftFields = populatedFieldCount(left);
        int rightFields = populatedFieldCount(right);
        return rightFields > leftFields ? right : left;
    }

    private int populatedFieldCount(TacticSearchResult result) {
        int count = 0;
        if (!StringValueSupport.isBlank(result.getBehaviorSummary())) {
            count++;
        }
        if (!StringValueSupport.isBlank(result.getToolSummary())) {
            count++;
        }
        if (result.getTags() != null && !result.getTags().isEmpty()) {
            count++;
        }
        if (result.getExplanation() != null) {
            count++;
        }
        return count;
    }

    private TacticSearchResult copy(TacticSearchResult result) {
        return TacticSearchResult.builder()
                .tacticId(result.getTacticId())
                .artifactStreamId(result.getArtifactStreamId())
                .originArtifactStreamId(result.getOriginArtifactStreamId())
                .artifactKey(result.getArtifactKey())
                .artifactType(result.getArtifactType())
                .title(result.getTitle())
                .aliases(result.getAliases())
                .contentRevisionId(result.getContentRevisionId())
                .intentSummary(result.getIntentSummary())
                .behaviorSummary(result.getBehaviorSummary())
                .toolSummary(result.getToolSummary())
                .outcomeSummary(result.getOutcomeSummary())
                .benchmarkSummary(result.getBenchmarkSummary())
                .approvalNotes(result.getApprovalNotes())
                .evidenceSnippets(result.getEvidenceSnippets())
                .taskFamilies(result.getTaskFamilies())
                .tags(result.getTags())
                .promotionState(result.getPromotionState())
                .rolloutStage(result.getRolloutStage())
                .score(result.getScore())
                .successRate(result.getSuccessRate())
                .benchmarkWinRate(result.getBenchmarkWinRate())
                .regressionFlags(result.getRegressionFlags())
                .recencyScore(result.getRecencyScore())
                .golemLocalUsageSuccess(result.getGolemLocalUsageSuccess())
                .embeddingStatus(result.getEmbeddingStatus())
                .updatedAt(result.getUpdatedAt())
                .explanation(result.getExplanation())
                .build();
    }

    private List<TacticSearchResult> concat(List<TacticSearchResult> left, List<TacticSearchResult> right) {
        List<TacticSearchResult> combined = new ArrayList<>();
        if (left != null) {
            combined.addAll(left);
        }
        if (right != null) {
            combined.addAll(right);
        }
        return combined;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
