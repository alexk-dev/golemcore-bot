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
        tacticSearchService = new TacticSearchService(queryExpansionService, bm25IndexService, runtimeConfigService);
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
}
