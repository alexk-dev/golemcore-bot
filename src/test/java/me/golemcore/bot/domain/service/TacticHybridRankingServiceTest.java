package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticHybridRankingServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private ModelSelectionService modelSelectionService;
    private TacticSearchMetricsService metricsService;
    private TacticHybridRankingService rankingService;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        metricsService = mock(TacticSearchMetricsService.class);
        rankingService = new TacticHybridRankingService(runtimeConfigService, modelSelectionService, metricsService);

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
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5.4", "high"));
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
        TacticSearchExplanation explanation = rankingService.rank(query(), lexicalHits(), vectorHits())
                .getFirst()
                .getExplanation();

        assertFalse(explanation.getRerankerVerdict().isBlank());
    }

    @Test
    void shouldDegradeCleanlyWhenCrossEncoderRerankerIsUnavailable() {
        when(modelSelectionService.resolveExplicitTier("deep"))
                .thenThrow(new IllegalStateException("reranker unavailable"));

        TacticSearchResult result = rankingService.rank(query(), lexicalHits(), vectorHits()).getFirst();

        assertTrue(result.getExplanation().getRerankerVerdict().contains("unavailable"));
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
