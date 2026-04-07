package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
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

    @Test
    void shouldSkipNullRecordsInGcCandidates() {
        List<TacticRecord> records = new ArrayList<>();
        records.add(null);
        records.add(TacticRecord.builder()
                .tacticId("stale-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build());
        when(tacticRecordService.getAll()).thenReturn(records);

        List<String> candidates = maintenanceService.findGcCandidates();

        assertEquals(List.of("stale-1"), candidates);
    }

    @Test
    void shouldSkipRecordsWithBlankTacticId() {
        TacticRecord blankId = TacticRecord.builder()
                .tacticId("  ")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(blankId));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldIncludeTacticsWithNullUpdatedAt() {
        TacticRecord noUpdatedAt = TacticRecord.builder()
                .tacticId("null-date-1")
                .promotionState("candidate")
                .updatedAt(null)
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(noUpdatedAt));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertEquals(List.of("null-date-1"), candidates);
    }

    @Test
    void shouldNotGcTacticsWithStrongBenchmarkWinRate() {
        TacticRecord staleButGoodBenchmark = TacticRecord.builder()
                .tacticId("benchmark-1")
                .promotionState("candidate")
                .benchmarkWinRate(0.65)
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(staleButGoodBenchmark));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldGcTacticsWithWeakQualitySignals() {
        TacticRecord weakSignals = TacticRecord.builder()
                .tacticId("weak-1")
                .promotionState("candidate")
                .successRate(0.5)
                .benchmarkWinRate(0.4)
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(weakSignals));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertEquals(List.of("weak-1"), candidates);
    }

    @Test
    void shouldContinueGcWhenSingleDeactivationFails() {
        TacticRecord stale1 = TacticRecord.builder()
                .tacticId("stale-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        TacticRecord stale2 = TacticRecord.builder()
                .tacticId("stale-2")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(stale1, stale2));
        doThrow(new RuntimeException("storage error")).when(tacticRecordService).deactivate("stale-1");

        List<String> deactivated = maintenanceService.collectGarbage();

        assertEquals(List.of("stale-2"), deactivated);
    }

    @Test
    void shouldCallCollectGarbageWithDefaultThreshold() {
        when(tacticRecordService.getAll()).thenReturn(List.of());

        List<String> deactivated = maintenanceService.collectGarbage();

        assertTrue(deactivated.isEmpty());
    }

    @Test
    void shouldSkipNullRecordsInDuplicateGrouping() {
        List<TacticRecord> records = new ArrayList<>();
        records.add(null);
        records.add(TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("skill:planner")
                .title("Planner A")
                .build());
        when(tacticRecordService.getAll()).thenReturn(records);

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertTrue(groups.isEmpty());
    }

    @Test
    void shouldSkipRecordsWithBlankArtifactKeyInDuplicateGrouping() {
        TacticRecord blankKey = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("  ")
                .title("Planner A")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(blankKey));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertTrue(groups.isEmpty());
    }

    @Test
    void shouldFindDuplicatesByTitleSimilarity() {
        // Jaccard: 6 shared tokens, 1 different each side -> 6/(6+1+1) = 6/8 = 0.75
        // Need higher: use identical titles except one word, with many tokens
        // 12 shared out of 13 unique = 12/13 = 0.923 >= 0.85
        TacticRecord first = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("key-a")
                .title("deploy docker containers to staging cluster with rolling update using blue green canary alpha")
                .promotionState("approved")
                .build();
        TacticRecord second = TacticRecord.builder()
                .tacticId("tactic-b")
                .artifactKey("key-b")
                .title("deploy docker containers to staging cluster with rolling update using blue green canary beta")
                .promotionState("candidate")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(first, second));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertEquals(1, groups.size());
        assertTrue(groups.getFirst().reason().startsWith("title_similarity:"));
    }

    @Test
    void shouldNotDuplicateGroupAlreadyGroupedByArtifactKey() {
        TacticRecord first = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("skill:planner")
                .title("deploy docker containers to production")
                .promotionState("approved")
                .build();
        TacticRecord second = TacticRecord.builder()
                .tacticId("tactic-b")
                .artifactKey("skill:planner")
                .title("deploy docker containers to production environment")
                .promotionState("candidate")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(first, second));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        // Should only have 1 group (artifact key), not an additional title similarity
        // group
        assertEquals(1, groups.size());
        assertTrue(groups.getFirst().reason().startsWith("artifact_key:"));
    }

    @Test
    void shouldPreferProtectedStateAsPrimaryInArtifactKeyGroup() {
        TacticRecord candidateFirst = TacticRecord.builder()
                .tacticId("tactic-candidate")
                .artifactKey("skill:planner")
                .title("Planner Candidate")
                .promotionState("candidate")
                .build();
        TacticRecord approvedSecond = TacticRecord.builder()
                .tacticId("tactic-approved")
                .artifactKey("skill:planner")
                .title("Planner Approved")
                .promotionState("approved")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(candidateFirst, approvedSecond));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertEquals(1, groups.size());
        assertEquals("tactic-approved", groups.getFirst().primaryId());
        assertTrue(groups.getFirst().secondaryIds().contains("tactic-candidate"));
    }

    @Test
    void shouldPreferStrongQualitySignalAsPrimaryWhenNoProtectedState() {
        TacticRecord weakFirst = TacticRecord.builder()
                .tacticId("tactic-weak")
                .artifactKey("skill:planner")
                .title("Planner Weak")
                .promotionState("candidate")
                .successRate(0.3)
                .build();
        TacticRecord strongSecond = TacticRecord.builder()
                .tacticId("tactic-strong")
                .artifactKey("skill:planner")
                .title("Planner Strong")
                .promotionState("candidate")
                .successRate(0.9)
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(weakFirst, strongSecond));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertEquals(1, groups.size());
        assertEquals("tactic-strong", groups.getFirst().primaryId());
    }

    @Test
    void shouldMergeWhenPrimaryHasNullAliases() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(null)
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
        assertTrue(primary.getAliases().contains("alias-b"));
        assertTrue(primary.getAliases().contains("Secondary tactic"));
    }

    @Test
    void shouldMergeWhenSecondaryHasNullAliases() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(List.of("alias-a"))
                .evidenceSnippets(List.of("evidence-1"))
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Secondary tactic")
                .aliases(null)
                .evidenceSnippets(null)
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        verify(tacticRecordService).save(primary);
        assertTrue(primary.getAliases().contains("alias-a"));
        assertTrue(primary.getAliases().contains("Secondary tactic"));
    }

    @Test
    void shouldMergeWhenPrimaryHasNullEvidence() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(List.of("alias-a"))
                .evidenceSnippets(null)
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
        assertTrue(primary.getEvidenceSnippets().contains("evidence-2"));
    }

    @Test
    void shouldNotDuplicateAliasesOnMerge() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(List.of("shared-alias"))
                .evidenceSnippets(List.of())
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Primary tactic")
                .aliases(List.of("shared-alias"))
                .evidenceSnippets(List.of())
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        long aliasCount = primary.getAliases().stream()
                .filter("shared-alias"::equals)
                .count();
        assertEquals(1, aliasCount);
    }

    @Test
    void shouldNotDuplicateEvidenceOnMerge() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(List.of())
                .evidenceSnippets(List.of("shared-evidence"))
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Secondary tactic")
                .aliases(List.of())
                .evidenceSnippets(List.of("shared-evidence"))
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        long evidenceCount = primary.getEvidenceSnippets().stream()
                .filter("shared-evidence"::equals)
                .count();
        assertEquals(1, evidenceCount);
    }

    @Test
    void shouldThrowWhenPrimaryNotFoundOnMerge() {
        when(tacticRecordService.getById("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> maintenanceService.merge("missing", "secondary-1"));
    }

    @Test
    void shouldThrowWhenSecondaryNotFoundOnMerge() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary")
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> maintenanceService.merge("primary-1", "missing"));
    }

    @Test
    void shouldSetUpdatedAtOnPrimaryAfterMerge() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary tactic")
                .aliases(List.of())
                .evidenceSnippets(List.of())
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Secondary tactic")
                .aliases(List.of())
                .evidenceSnippets(List.of())
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        assertNotNull(primary.getUpdatedAt());
        assertEquals(Instant.now(clock), primary.getUpdatedAt());
    }

    @Test
    void shouldSkipRecordsWithNullTitleInTitleSimilarityCheck() {
        TacticRecord withTitle = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("key-a")
                .title("deploy docker containers")
                .promotionState("candidate")
                .build();
        TacticRecord nullTitle = TacticRecord.builder()
                .tacticId("tactic-b")
                .artifactKey("key-b")
                .title(null)
                .promotionState("candidate")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(withTitle, nullTitle));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertTrue(groups.isEmpty());
    }

    @Test
    void shouldNotGroupDissimilarTitlesByTitleSimilarity() {
        TacticRecord first = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactKey("key-a")
                .title("deploy docker containers")
                .promotionState("candidate")
                .build();
        TacticRecord second = TacticRecord.builder()
                .tacticId("tactic-b")
                .artifactKey("key-b")
                .title("optimize database queries performance")
                .promotionState("candidate")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(first, second));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertTrue(groups.isEmpty());
    }

    @Test
    void shouldHandleNullAliasInSecondaryDuringMerge() {
        List<String> secondaryAliases = new ArrayList<>();
        secondaryAliases.add("valid-alias");
        secondaryAliases.add(null);
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary")
                .aliases(List.of())
                .evidenceSnippets(List.of())
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Secondary")
                .aliases(secondaryAliases)
                .evidenceSnippets(List.of())
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        assertTrue(primary.getAliases().contains("valid-alias"));
        // null aliases should be skipped
        assertEquals(2, primary.getAliases().size()); // "valid-alias" + "Secondary"
    }

    @Test
    void shouldHandleNullEvidenceSnippetInSecondaryDuringMerge() {
        List<String> secondaryEvidence = new ArrayList<>();
        secondaryEvidence.add("valid-evidence");
        secondaryEvidence.add(null);
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary")
                .aliases(List.of())
                .evidenceSnippets(List.of())
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title(null)
                .aliases(List.of())
                .evidenceSnippets(secondaryEvidence)
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        assertTrue(primary.getEvidenceSnippets().contains("valid-evidence"));
        // null snippets should be skipped
        assertEquals(1, primary.getEvidenceSnippets().size());
    }

    @Test
    void shouldPreferProtectedStateAsPrimaryInTitleSimilarityDuplicates() {
        // Jaccard: 13 shared out of 15 unique = 0.867 >= 0.85
        TacticRecord candidateFirst = TacticRecord.builder()
                .tacticId("tactic-candidate")
                .artifactKey("key-a")
                .title("deploy docker containers to staging cluster with rolling update using blue green canary alpha")
                .promotionState("candidate")
                .build();
        TacticRecord approvedSecond = TacticRecord.builder()
                .tacticId("tactic-approved")
                .artifactKey("key-b")
                .title("deploy docker containers to staging cluster with rolling update using blue green canary beta")
                .promotionState("approved")
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(candidateFirst, approvedSecond));

        List<TacticMaintenanceService.DuplicateGroup> groups = maintenanceService.findDuplicateGroups();

        assertEquals(1, groups.size());
        // left (candidateFirst) is not protected, so primaryId = right
        // (tactic-approved)
        assertEquals("tactic-approved", groups.getFirst().primaryId());
        assertTrue(groups.getFirst().secondaryIds().contains("tactic-candidate"));
    }

    @Test
    void shouldNotGcTacticsInActiveState() {
        TacticRecord activeTactic = TacticRecord.builder()
                .tacticId("active-1")
                .promotionState("ACTIVE")
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(activeTactic));

        List<String> candidates = maintenanceService.findGcCandidates();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldCollectGarbageWithCustomThreshold() {
        TacticRecord stale = TacticRecord.builder()
                .tacticId("stale-1")
                .promotionState("candidate")
                .updatedAt(Instant.parse("2026-04-04T00:00:00Z"))
                .build();
        when(tacticRecordService.getAll()).thenReturn(List.of(stale));

        List<String> deactivated = maintenanceService.collectGarbage(Duration.ofDays(1));

        assertEquals(List.of("stale-1"), deactivated);
        verify(tacticRecordService).deactivate("stale-1");
    }

    @Test
    void shouldSkipSecondaryTitleAdditionWhenAlreadyInAliases() {
        TacticRecord primary = TacticRecord.builder()
                .tacticId("primary-1")
                .title("Primary")
                .aliases(List.of("Secondary tactic"))
                .evidenceSnippets(List.of())
                .build();
        TacticRecord secondary = TacticRecord.builder()
                .tacticId("secondary-1")
                .title("Secondary tactic")
                .aliases(List.of())
                .evidenceSnippets(List.of())
                .build();
        when(tacticRecordService.getById("primary-1")).thenReturn(Optional.of(primary));
        when(tacticRecordService.getById("secondary-1")).thenReturn(Optional.of(secondary));

        maintenanceService.merge("primary-1", "secondary-1");

        long titleCount = primary.getAliases().stream()
                .filter("Secondary tactic"::equals)
                .count();
        assertEquals(1, titleCount);
    }
}
