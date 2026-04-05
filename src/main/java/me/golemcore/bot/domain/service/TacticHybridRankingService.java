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

import me.golemcore.bot.domain.model.RuntimeConfig;
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

/**
 * Hybrid tactic ranking with RRF, quality priors, diversity, penalties, and a
 * tier-resolved reranker verdict.
 */
@Service
public class TacticHybridRankingService {

    private static final double RRF_K = 60.0d;
    private static final double RRF_SCALE = 15.0d;

    private final RuntimeConfigService runtimeConfigService;
    private final TacticSearchMetricsService metricsService;
    private final TacticCrossEncoderRerankerService rerankerService;

    public TacticHybridRankingService(
            RuntimeConfigService runtimeConfigService,
            TacticSearchMetricsService metricsService,
            TacticCrossEncoderRerankerService rerankerService) {
        this.runtimeConfigService = runtimeConfigService;
        this.metricsService = metricsService;
        this.rerankerService = rerankerService;
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

        List<TacticSearchResult> diversified = applyMmr(prelim);
        return applyRerankerVerdict(query, diversified, vectorHits != null && !vectorHits.isEmpty());
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

    private List<TacticSearchResult> applyRerankerVerdict(
            TacticSearchQuery query,
            List<TacticSearchResult> ranked,
            boolean vectorAvailable) {
        String searchMode = vectorAvailable ? "hybrid" : "bm25";
        RuntimeConfig.SelfEvolvingTacticRerankConfig rerankConfig = runtimeConfigService.getSelfEvolvingConfig()
                .getTactics()
                .getSearch()
                .getRerank();
        if (rerankConfig == null || !Boolean.TRUE.equals(rerankConfig.getCrossEncoder())) {
            metricsService.recordActiveMode(searchMode, null);
            ranked.forEach(result -> result.getExplanation().setRerankerVerdict("disabled"));
            return ranked;
        }
        try {
            Map<String, TacticCrossEncoderRerankerService.RerankedCandidate> rerankedCandidates = rerankerService
                    .rerank(query, ranked, rerankConfig.getTier(), rerankConfig.getTimeoutMs()).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            TacticCrossEncoderRerankerService.RerankedCandidate::tacticId,
                            candidate -> candidate,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (TacticSearchResult result : ranked) {
                TacticCrossEncoderRerankerService.RerankedCandidate rerankedCandidate = rerankedCandidates
                        .get(result.getTacticId());
                if (rerankedCandidate != null) {
                    result.setScore(result.getScore() + rerankedCandidate.score());
                    result.getExplanation().setRerankerVerdict(rerankedCandidate.verdict());
                } else {
                    result.getExplanation().setRerankerVerdict("no rerank result");
                }
                result.getExplanation().setSearchMode(searchMode);
                result.getExplanation().setFinalScore(result.getScore());
            }
            ranked.sort(Comparator.comparing(TacticSearchResult::getScore).reversed());
            metricsService.recordActiveMode(searchMode, null);
            return ranked;
        } catch (RuntimeException exception) {
            ranked.forEach(result -> {
                result.getExplanation().setRerankerVerdict("unavailable: " + exception.getMessage());
                result.getExplanation().setSearchMode(searchMode);
                result.getExplanation().setDegradedReason(exception.getMessage());
            });
            metricsService.recordQueryFailure(exception.getMessage());
            metricsService.recordActiveMode(searchMode, exception.getMessage());
            return ranked;
        }
    }

    private double rrf(Integer rank) {
        return rank == null ? 0.0d : RRF_SCALE / (RRF_K + rank);
    }

    private double qualityPrior(TacticSearchResult candidate) {
        double weightedSum = 0.0d;
        double weightTotal = 0.0d;
        if (candidate.getSuccessRate() != null) {
            weightedSum += candidate.getSuccessRate() * 0.35d;
            weightTotal += 0.35d;
        }
        if (candidate.getBenchmarkWinRate() != null) {
            weightedSum += candidate.getBenchmarkWinRate() * 0.25d;
            weightTotal += 0.25d;
        }
        if (candidate.getRecencyScore() != null) {
            weightedSum += candidate.getRecencyScore() * 0.15d;
            weightTotal += 0.15d;
        }
        if (candidate.getGolemLocalUsageSuccess() != null) {
            weightedSum += candidate.getGolemLocalUsageSuccess() * 0.15d;
            weightTotal += 0.15d;
        }
        // When no signals have been observed yet, use a pessimistic prior (0.3)
        // rather than 0.5: a brand-new tactic should not outrank tactics with
        // at least one recorded measurement just because nothing is known.
        double signalScore = weightTotal > 0.0d ? weightedSum / weightTotal : 0.3d;
        double promotionBoost = switch (normalize(candidate.getPromotionState())) {
        case "active" -> 0.12d;
        case "approved" -> 0.08d;
        case "candidate" -> -0.05d;
        case "reverted" -> -0.20d;
        default -> 0.0d;
        };
        return signalScore + promotionBoost;
    }

    private double negativeMemoryPenalty(TacticSearchResult candidate) {
        int regressionCount = candidate.getRegressionFlags() != null ? candidate.getRegressionFlags().size() : 0;
        return Math.min(0.25d, regressionCount * 0.08d);
    }

    private double personalizationBoost(TacticSearchQuery query, TacticSearchResult candidate) {
        if (query.getAvailableTools() == null || query.getAvailableTools().isEmpty()) {
            return 0.0d;
        }
        String toolSummary = normalize(candidate.getToolSummary());
        double boost = 0.0d;
        for (String tool : query.getAvailableTools()) {
            if (!StringValueSupport.isBlank(tool) && toolSummary.contains(normalize(tool))) {
                boost += 0.04d;
            }
        }
        return Math.min(0.12d, boost);
    }

    private double diversityPenalty(TacticSearchResult candidate, List<TacticSearchResult> selected) {
        double penalty = 0.0d;
        for (TacticSearchResult existing : selected) {
            if (normalize(candidate.getArtifactKey()).equals(normalize(existing.getArtifactKey()))) {
                penalty = Math.max(penalty, 0.05d);
            }
            penalty = Math.max(penalty, overlapPenalty(candidate, existing));
        }
        return penalty;
    }

    private double overlapPenalty(TacticSearchResult left, TacticSearchResult right) {
        LinkedHashSet<String> leftTokens = tokens(
                left.getTitle() + " " + left.getBehaviorSummary() + " " + left.getToolSummary());
        LinkedHashSet<String> rightTokens = tokens(
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
        return 0.04d * ((double) intersection / Math.max(1, Math.min(leftTokens.size(), rightTokens.size())));
    }

    private LinkedHashSet<String> tokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
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
