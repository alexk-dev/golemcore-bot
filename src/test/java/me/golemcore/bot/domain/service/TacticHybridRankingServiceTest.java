package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticHybridRankingServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private TacticSearchMetricsService metricsService;
    private TacticCrossEncoderRerankerService rerankerService;
    private TacticHybridRankingService rankingService;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        metricsService = mock(TacticSearchMetricsService.class);
        rerankerService = mock(TacticCrossEncoderRerankerService.class);
        rankingService = new TacticHybridRankingService(runtimeConfigService, metricsService, rerankerService);

        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .enabled(true)
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .mode("hybrid")
                                        .rerank(RuntimeConfig.SelfEvolvingTacticRerankConfig.builder()
                                                .crossEncoder(true)
                                                .tier("deep")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(runtimeConfig.getSelfEvolving());
    }

    @Test
    void shouldFuseBm25AndVectorCandidatesWithRrf() {
        TacticSearchExplanation explanation = rankingService.rank(query(), lexicalHits(), vectorHits())
                .getFirst()
                .getExplanation();

        assertTrue(explanation.getRrfScore() > 0.0d);
    }

    @Test
    void shouldExposeQualityPriorsAndPenalties() {
        TacticSearchExplanation explanation = rankingService.rank(query(), lexicalHits(), vectorHits())
                .getFirst()
                .getExplanation();

        assertNotNull(explanation.getQualityPrior());
        assertNotNull(explanation.getNegativeMemoryPenalty());
        assertNotNull(explanation.getPersonalizationBoost());
        assertNotNull(explanation.getMmrDiversityAdjustment());
    }

    @Test
    void shouldFallBackToBm25OnlyWhenVectorCandidatesAreUnavailable() {
        TacticSearchResult result = rankingService.rank(query(), lexicalHits(), List.of()).getFirst();

        assertEquals("bm25", result.getExplanation().getSearchMode());
    }

    @Test
    void shouldSurfaceCrossEncoderRerankerVerdict() {
        when(rerankerService.rerank(any(), anyList(), eq("deep"), anyInt())).thenReturn(List.of(
                new TacticCrossEncoderRerankerService.RerankedCandidate("planner", 0.08d,
                        "tier deep via gpt-5.4/high"),
                new TacticCrossEncoderRerankerService.RerankedCandidate("rollback", 0.01d,
                        "tier deep via gpt-5.4/high")));

        TacticSearchExplanation explanation = rankingService.rank(query(), lexicalHits(), vectorHits())
                .getFirst()
                .getExplanation();

        assertTrue(explanation.getRerankerVerdict().contains("gpt-5.4"));
    }

    @Test
    void shouldDegradeCleanlyWhenCrossEncoderRerankerIsUnavailable() {
        when(rerankerService.rerank(any(), anyList(), eq("deep"), anyInt()))
                .thenThrow(new IllegalStateException("reranker unavailable"));

        TacticSearchResult result = rankingService.rank(query(), lexicalHits(), vectorHits()).getFirst();

        assertTrue(result.getExplanation().getRerankerVerdict().contains("unavailable"));
    }

    @Test
    void shouldStillComputeSearchScoringWhenTacticQualityMetricsAreUnavailable() {
        TacticSearchResult sparse = TacticSearchResult.builder()
                .tacticId("sparse")
                .artifactStreamId("stream-sparse")
                .artifactKey("skill:sparse")
                .artifactType("skill")
                .title("Sparse")
                .promotionState("active")
                .rolloutStage("active")
                .toolSummary("shell")
                .successRate(null)
                .benchmarkWinRate(null)
                .regressionFlags(List.of())
                .recencyScore(null)
                .golemLocalUsageSuccess(null)
                .tags(List.of("planner"))
                .updatedAt(Instant.parse("2026-04-01T23:30:00Z"))
                .build();

        TacticSearchResult result = rankingService.rank(query(), List.of(sparse), List.of(sparse)).getFirst();

        assertNotNull(result.getExplanation().getQualityPrior());
        assertNotNull(result.getExplanation().getRrfScore());
        assertNotNull(result.getExplanation().getFinalScore());
        assertFalse(Double.isNaN(result.getExplanation().getFinalScore()));
        assertEquals("hybrid", result.getExplanation().getSearchMode());
    }

    @Test
    void shouldTreatUnavailableQualityMetricsAsNeutralInsteadOfObservedZeros() {
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .enabled(true)
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .mode("hybrid")
                                        .rerank(RuntimeConfig.SelfEvolvingTacticRerankConfig.builder()
                                                .crossEncoder(false)
                                                .tier("deep")
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(runtimeConfig.getSelfEvolving());

        TacticSearchResult unknownMetrics = TacticSearchResult.builder()
                .tacticId("unknown-metrics")
                .artifactStreamId("stream-unknown")
                .artifactKey("skill:unknown")
                .artifactType("skill")
                .title("Unknown metrics")
                .promotionState("active")
                .rolloutStage("active")
                .toolSummary("shell")
                .successRate(1.0d)
                .benchmarkWinRate(null)
                .regressionFlags(List.of())
                .recencyScore(1.0d)
                .golemLocalUsageSuccess(null)
                .updatedAt(Instant.parse("2026-04-01T23:30:00Z"))
                .build();
        TacticSearchResult observedZeros = TacticSearchResult.builder()
                .tacticId("observed-zeros")
                .artifactStreamId("stream-zero")
                .artifactKey("skill:zeros")
                .artifactType("skill")
                .title("Observed zeros")
                .promotionState("active")
                .rolloutStage("active")
                .toolSummary("shell")
                .successRate(1.0d)
                .benchmarkWinRate(0.0d)
                .regressionFlags(List.of())
                .recencyScore(1.0d)
                .golemLocalUsageSuccess(0.0d)
                .updatedAt(Instant.parse("2026-04-01T23:30:00Z"))
                .build();

        Map<String, Double> qualityPriors = rankingService.rank(
                query(),
                List.of(unknownMetrics, observedZeros),
                List.of(unknownMetrics, observedZeros)).stream()
                .collect(Collectors.toMap(
                        TacticSearchResult::getTacticId,
                        result -> result.getExplanation().getQualityPrior()));

        assertTrue(qualityPriors.get("unknown-metrics") > qualityPriors.get("observed-zeros"));
    }

    private TacticSearchQuery query() {
        return TacticSearchQuery.builder()
                .rawQuery("recover failed shell command")
                .queryViews(List.of("recover", "shell", "failure"))
                .availableTools(List.of("shell", "git"))
                .build();
    }

    private List<TacticSearchResult> lexicalHits() {
        return List.of(
                tactic("planner", "active", 0.92d, 0.80d, List.of(), "shell git", List.of("planner")),
                tactic("rollback", "approved", 0.76d, 0.55d, List.of("regression"), "shell", List.of("recovery")));
    }

    private List<TacticSearchResult> vectorHits() {
        return List.of(
                tactic("planner", "active", 0.92d, 0.80d, List.of(), "shell git", List.of("planner")),
                tactic("rollback", "approved", 0.76d, 0.55d, List.of("regression"), "shell", List.of("recovery")));
    }

    private TacticSearchResult tactic(
            String tacticId,
            String promotionState,
            Double successRate,
            Double benchmarkWinRate,
            List<String> regressionFlags,
            String toolSummary,
            List<String> tags) {
        return TacticSearchResult.builder()
                .tacticId(tacticId)
                .artifactStreamId("stream-" + tacticId)
                .artifactKey("skill:" + tacticId)
                .artifactType("skill")
                .title(Character.toUpperCase(tacticId.charAt(0)) + tacticId.substring(1))
                .promotionState(promotionState)
                .rolloutStage(promotionState)
                .toolSummary(toolSummary)
                .successRate(successRate)
                .benchmarkWinRate(benchmarkWinRate)
                .regressionFlags(regressionFlags)
                .recencyScore(0.75d)
                .golemLocalUsageSuccess(0.88d)
                .tags(tags)
                .updatedAt(Instant.parse("2026-04-01T23:30:00Z"))
                .build();
    }
}
