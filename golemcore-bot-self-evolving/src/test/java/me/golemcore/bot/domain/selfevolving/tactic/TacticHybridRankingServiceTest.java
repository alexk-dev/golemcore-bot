package me.golemcore.bot.domain.selfevolving.tactic;

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
import static org.mockito.Mockito.mock;

class TacticHybridRankingServiceTest {

    private TacticSearchMetricsService metricsService;
    private TacticHybridRankingService rankingService;

    @BeforeEach
    void setUp() {
        metricsService = mock(TacticSearchMetricsService.class);
        rankingService = new TacticHybridRankingService(metricsService);
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
