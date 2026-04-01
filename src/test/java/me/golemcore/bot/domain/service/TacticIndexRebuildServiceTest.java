package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticIndexRebuildServiceTest {

    private TacticRecordService tacticRecordService;
    private TacticBm25IndexService bm25IndexService;
    private TacticIndexRebuildService rebuildService;

    @BeforeEach
    void setUp() {
        tacticRecordService = mock(TacticRecordService.class);
        bm25IndexService = new TacticBm25IndexService();
        rebuildService = new TacticIndexRebuildService(
                tacticRecordService,
                new TacticSearchDocumentAssembler(),
                bm25IndexService,
                Clock.fixed(Instant.parse("2026-04-01T22:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldRebuildLexicalIndexOnTacticAndPromotionChanges() {
        when(tacticRecordService.getAll()).thenReturn(List.of(TacticRecord.builder()
                .tacticId("planner")
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner tactic")
                .intentSummary("Plan delivery work")
                .promotionState("approved")
                .rolloutStage("active")
                .updatedAt(Instant.parse("2026-04-01T22:00:00Z"))
                .build()));

        rebuildService.onTacticChanged("planner");
        rebuildService.onPromotionStateChanged("stream-1");

        assertEquals(1, bm25IndexService.snapshot().documents().size());
        assertEquals(2, rebuildService.snapshot().rebuildCount());
    }
}
