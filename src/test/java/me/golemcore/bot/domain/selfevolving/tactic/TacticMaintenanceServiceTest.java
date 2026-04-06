package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticMaintenanceServiceTest {

    private TacticRecordService tacticRecordService;
    private TacticMaintenanceService maintenanceService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        tacticRecordService = mock(TacticRecordService.class);
        clock = Clock.fixed(Instant.parse("2026-04-06T12:00:00Z"), ZoneOffset.UTC);
        maintenanceService = new TacticMaintenanceService(tacticRecordService, clock);
    }

    @Test
    void shouldIdentifyStaleCandidateTacticsForGc() {
        TacticRecord staleCandidate = TacticRecord.builder()
                .tacticId("stale-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        TacticRecord freshCandidate = TacticRecord.builder()
                .tacticId("fresh-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-04-05T00:00:00Z"))
                .build();
        TacticRecord approvedOld = TacticRecord.builder()
                .tacticId("approved-1")
                .promotionState("approved")
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(staleCandidate, freshCandidate, approvedOld));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertEquals(List.of("stale-1"), candidates);
    }

    @Test
    void shouldNotGcTacticsWithStrongQualitySignal() {
        TacticRecord staleButGood = TacticRecord.builder()
                .tacticId("good-1")
                .promotionState("candidate")
                .successRate(0.8)
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(staleButGood));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldDeactivateStaleTacticsOnCollect() {
        TacticRecord stale = TacticRecord.builder()
                .tacticId("stale-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(stale));

        List<String> deactivated = maintenanceService.collectGarbage();

        assertEquals(List.of("stale-1"), deactivated);
        verify(tacticRecordService).deactivate("stale-1");
    }

    @Test
    void shouldFindDuplicatesByArtifactKey() {
        TacticRecord first = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("skill:planner")
                .title("Planner A")
                .promotionState("approved")
                .build();
        TacticRecord second = TacticRecord.builder()
                .tacticId("tactic-b")
                .artifactKey("skill:planner")
                .title("Planner B")
                .promotionState("candidate")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(first, second));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertEquals(1, groups.size());
        assertEquals("tactic-a", groups.getFirst().primaryId());
        assertTrue(groups.getFirst().secondaryIds().contains("tactic-b"));
    }

    @Test
    void shouldMergeSecondaryIntoPrimary() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(List.of("alias-a"))
                .evidenceSnippets(List.of("evidence-1"))
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Secondary tactic")
                .aliases(List.of("alias-b"))
                .evidenceSnippets(List.of("evidence-2"))
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        verify(tacticRecordService).save(primary);
        verify(tacticRecordService).deactivate("secondary-1");
        assertTrue(primary.getAliases().contains("alias-b"));
        assertTrue(primary.getAliases().contains("Secondary tactic"));
        assertTrue(primary.getEvidenceSnippets().contains("evidence-2"));
    }

    @Test
    void shouldReturnEmptyWhenNoStaleTactics() {
        TacticRecord active = TacticRecord.builder()
                .tacticId("active-1")
                .promotionState("active")
                .updatedAt(Instant.parse("2026-04-05T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(active));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldRespectCustomStalenessThreshold() {
        TacticRecord candidate = TacticRecord.builder()
                .tacticId("candidate-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-04-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(candidate));

        List<String> withDefaultThreshold = maintenanceService.findGcCandidates();
        List<String> withShortThreshold = maintenanceService.findGcCandidates(Duration.ofDays(3));

        assertTrue(withDefaultThreshold.isEmpty());
        assertEquals(List.of("candidate-1"), withShortThreshold);
    }
}
