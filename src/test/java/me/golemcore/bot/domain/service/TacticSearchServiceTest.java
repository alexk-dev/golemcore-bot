package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticSearchServiceTest {

    private TacticQueryExpansionService queryExpansionService;
    private TacticSearchService tacticSearchService;

    @BeforeEach
    void setUp() {
        TacticRecordService tacticRecordService = mock(TacticRecordService.class);
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("approved-planner", "approved", "approved", "Planner tactic", "recover failed shell command"),
                tactic("candidate-planner", "candidate", "proposed", "Candidate planner",
                        "recover failed shell command"),
                tactic("reverted-planner", "reverted", "reverted", "Reverted planner",
                        "recover failed shell command")));

        TacticBm25IndexService bm25IndexService = new TacticBm25IndexService();
        TacticIndexRebuildService indexRebuildService = new TacticIndexRebuildService(
                tacticRecordService,
                new TacticSearchDocumentAssembler(),
                bm25IndexService,
                mock(TacticEmbeddingIndexService.class),
                Clock.fixed(Instant.parse("2026-04-01T23:00:00Z"), ZoneOffset.UTC));
        indexRebuildService.rebuildAll();

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .enabled(true)
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .mode("bm25")
                                        .build())
                                .build())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(runtimeConfig.getSelfEvolving());

        queryExpansionService = new TacticQueryExpansionService();
        tacticSearchService = new TacticSearchService(queryExpansionService, bm25IndexService, runtimeConfigService,
                null, null, null);
    }

    @Test
    void shouldExpandQueryIntoIntentToolAndRecoveryViews() {
        assertTrue(queryExpansionService.expand("recover from failed shell command").getQueryViews()
                .containsAll(List.of("recover", "shell", "failure")));
    }

    @Test
    void shouldFilterOutCandidateAndRevertedTacticsByDefault() {
        List<TacticSearchResult> results = tacticSearchService.search("planner");

        assertEquals(1, results.size());
        assertTrue(results.stream()
                .allMatch(result -> List.of("approved", "active").contains(result.getPromotionState())));
        assertEquals("approved-planner", results.getFirst().getTacticId());
        assertNotNull(results.getFirst().getExplanation());
    }

    @Test
    void shouldFilterOutIneligibleVectorOnlyResultsAfterHybridRanking() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .enabled(true)
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .mode("hybrid")
                                        .build())
                                .build())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(runtimeConfig.getSelfEvolving());

        TacticBm25IndexService bm25IndexService = new TacticBm25IndexService();
        TacticEmbeddingIndexService embeddingIndexService = mock(TacticEmbeddingIndexService.class);
        TacticHybridRankingService rankingService = mock(TacticHybridRankingService.class);
        TacticSearchService hybridSearchService = new TacticSearchService(
                queryExpansionService,
                bm25IndexService,
                runtimeConfigService,
                null,
                embeddingIndexService,
                rankingService);

        TacticSearchResult active = result("active-vector", "active");
        TacticSearchResult candidate = result("candidate-vector", "candidate");
        TacticSearchResult reverted = result("reverted-vector", "reverted");
        when(embeddingIndexService.search(any())).thenReturn(List.of(active, candidate, reverted));
        when(rankingService.rank(any(), anyList(), anyList())).thenReturn(List.of(active, candidate, reverted));

        List<TacticSearchResult> results = hybridSearchService.search("recover shell failure");

        assertEquals(List.of("active-vector"), results.stream().map(TacticSearchResult::getTacticId).toList());
        assertTrue(results.stream().allMatch(result -> Boolean.TRUE.equals(result.getExplanation().getEligible())));
    }

    @Test
    void shouldSearchWhenSelfEvolvingIsEnabledEvenIfLegacyTacticsFlagIsFalse() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig runtimeConfig = RuntimeConfig.builder()
                .selfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                        .enabled(true)
                        .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                                .enabled(false)
                                .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                        .mode("hybrid")
                                        .build())
                                .build())
                        .build())
                .build();
        when(runtimeConfigService.getRuntimeConfig()).thenReturn(runtimeConfig);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(runtimeConfig.getSelfEvolving());

        TacticRecordService tacticRecordService = mock(TacticRecordService.class);
        when(tacticRecordService.getAll()).thenReturn(List.of(
                tactic("approved-planner", "approved", "approved", "Planner tactic", "recover failed shell command")));
        TacticBm25IndexService bm25IndexService = new TacticBm25IndexService();
        TacticIndexRebuildService indexRebuildService = new TacticIndexRebuildService(
                tacticRecordService,
                new TacticSearchDocumentAssembler(),
                bm25IndexService,
                mock(TacticEmbeddingIndexService.class),
                Clock.fixed(Instant.parse("2026-04-01T23:00:00Z"), ZoneOffset.UTC));
        indexRebuildService.rebuildAll();
        TacticSearchService searchService = new TacticSearchService(
                queryExpansionService,
                bm25IndexService,
                runtimeConfigService,
                null,
                null,
                null);

        List<TacticSearchResult> results = searchService.search("planner");

        assertEquals(1, results.size());
        assertEquals("approved-planner", results.getFirst().getTacticId());
    }

    @Test
    void shouldPreserveSemanticTacticFieldsInSearchResults() {
        List<TacticSearchResult> results = tacticSearchService.search("recover failed shell command");

        assertEquals(1, results.size());
        assertEquals("recover failed shell command", results.getFirst().getIntentSummary());
        assertEquals("recover failed shell command", results.getFirst().getBehaviorSummary());
        assertEquals("shell", results.getFirst().getToolSummary());
    }

    private TacticRecord tactic(String tacticId, String promotionState, String rolloutStage, String title,
            String summary) {
        return TacticRecord.builder()
                .tacticId(tacticId)
                .artifactStreamId("stream-" + tacticId)
                .artifactKey("skill:" + tacticId)
                .artifactType("skill")
                .title(title)
                .intentSummary(summary)
                .behaviorSummary(summary)
                .toolSummary("shell")
                .promotionState(promotionState)
                .rolloutStage(rolloutStage)
                .successRate(0.9d)
                .benchmarkWinRate(0.7d)
                .recencyScore(0.8d)
                .golemLocalUsageSuccess(0.85d)
                .updatedAt(Instant.parse("2026-04-01T22:00:00Z"))
                .build();
    }

    private TacticSearchResult result(String tacticId, String promotionState) {
        return TacticSearchResult.builder()
                .tacticId(tacticId)
                .artifactStreamId("stream-" + tacticId)
                .artifactKey("skill:" + tacticId)
                .artifactType("skill")
                .title(tacticId)
                .promotionState(promotionState)
                .rolloutStage(promotionState)
                .score(0.9d)
                .explanation(me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .eligible(true)
                        .build())
                .build();
    }
}
