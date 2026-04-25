package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaignVerdict;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticOutcomeEntry;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactBundleService;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;

class TacticQualityMetricsServiceTest {

    private ArtifactBundleService artifactBundleService;
    private SelfEvolvingRunService selfEvolvingRunService;
    private TacticUsageAttributionService tacticUsageAttributionService;
    private ObservedTacticMetricsCalculator observedTacticMetricsCalculator;
    private BenchmarkLabService benchmarkLabService;
    private TacticOutcomeJournalService tacticOutcomeJournalService;
    private Clock clock;
    private TacticQualityMetricsService tacticQualityMetricsService;

    @BeforeEach
    void setUp() {
        artifactBundleService = mock(ArtifactBundleService.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        tacticOutcomeJournalService = mock(TacticOutcomeJournalService.class);
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of());
        clock = Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC);
        tacticUsageAttributionService = new TacticUsageAttributionService();
        observedTacticMetricsCalculator = new ObservedTacticMetricsCalculator(clock);
        tacticQualityMetricsService = new TacticQualityMetricsService(
                artifactBundleService,
                selfEvolvingRunService,
                tacticUsageAttributionService,
                observedTacticMetricsCalculator,
                benchmarkLabService,
                tacticOutcomeJournalService,
                clock);
    }

    @Test
    void shouldDeriveObservedSuccessMetricsAndRecencyFromMatchingRuns() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("revision-1")
                .updatedAt(Instant.parse("2026-03-20T00:00:00Z"))
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-1")
                        .artifactRevisionBindings(java.util.Map.of("stream-1", "revision-1"))
                        .activatedAt(Instant.parse("2026-04-05T09:00:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-2")
                        .artifactRevisionBindings(java.util.Map.of("stream-1", "revision-1"))
                        .activatedAt(Instant.parse("2026-04-04T08:00:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-3")
                        .artifactRevisionBindings(java.util.Map.of("stream-9", "revision-9"))
                        .activatedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-1")
                        .artifactBundleId("bundle-1")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .build(),
                RunRecord.builder()
                        .id("run-2")
                        .artifactBundleId("bundle-2")
                        .status("FAILED")
                        .completedAt(Instant.parse("2026-04-04T12:00:00Z"))
                        .build(),
                RunRecord.builder()
                        .id("run-3")
                        .artifactBundleId("bundle-3")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-04-05T11:30:00Z"))
                        .build()));
        when(selfEvolvingRunService.findVerdict("run-1")).thenReturn(Optional.of(RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .createdAt(Instant.parse("2026-04-05T11:00:30Z"))
                .build()));
        when(selfEvolvingRunService.findVerdict("run-2")).thenReturn(Optional.of(RunVerdict.builder()
                .runId("run-2")
                .outcomeStatus("FAILED")
                .createdAt(Instant.parse("2026-04-04T12:00:30Z"))
                .build()));
        when(selfEvolvingRunService.findVerdict("run-3")).thenReturn(Optional.of(RunVerdict.builder()
                .runId("run-3")
                .outcomeStatus("COMPLETED")
                .createdAt(Instant.parse("2026-04-05T11:30:30Z"))
                .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertEquals(0.5d, enriched.getSuccessRate());
        assertEquals(0.5d, enriched.getGolemLocalUsageSuccess());
        assertEquals(1.0d, enriched.getRecencyScore());
        assertNull(enriched.getBenchmarkWinRate());
    }

    @Test
    void shouldLeaveObservedMetricsUnavailableUntilMatchingVerdictsExist() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-2")
                .artifactStreamId("stream-2")
                .contentRevisionId("revision-2")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-4")
                        .artifactRevisionBindings(java.util.Map.of("stream-2", "revision-2"))
                        .build()));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-4")
                        .artifactBundleId("bundle-4")
                        .status("RUNNING")
                        .startedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.findVerdict("run-4")).thenReturn(Optional.empty());

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertNull(enriched.getSuccessRate());
        assertNull(enriched.getBenchmarkWinRate());
        assertNull(enriched.getGolemLocalUsageSuccess());
        assertEquals(1.0d, enriched.getRecencyScore());
    }

    @Test
    void shouldFallbackToRunStatusWhenVerdictOutcomeIsNotTerminal() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-3")
                .artifactStreamId("stream-3")
                .contentRevisionId("revision-3")
                .updatedAt(Instant.parse("2026-02-01T00:00:00Z"))
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-5")
                        .artifactRevisionBindings(java.util.Map.of("stream-3", "revision-3"))
                        .activatedAt(Instant.parse("2026-02-02T00:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-5")
                        .artifactBundleId("bundle-5")
                        .status("FAILED")
                        .completedAt(Instant.parse("2026-02-03T00:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.findVerdict("run-5")).thenReturn(Optional.of(RunVerdict.builder()
                .runId("run-5")
                .outcomeStatus("RUNNING")
                .createdAt(Instant.parse("2026-02-03T00:05:00Z"))
                .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertEquals(0.0d, enriched.getSuccessRate());
        assertEquals(0.0d, enriched.getGolemLocalUsageSuccess());
        assertEquals(0.0d, enriched.getRecencyScore());
        assertNull(enriched.getBenchmarkWinRate());
    }

    @Test
    void shouldReturnUnavailableMetricsWhenTacticCannotMatchAnyBundles() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-4")
                .artifactStreamId(" ")
                .contentRevisionId("revision-4")
                .build();
        List<ArtifactBundleRecord> bundles = new ArrayList<>();
        bundles.add(null);
        bundles.add(ArtifactBundleRecord.builder()
                .artifactRevisionBindings(java.util.Map.of("stream-4", "revision-4"))
                .build());
        bundles.add(ArtifactBundleRecord.builder()
                .id("bundle-6")
                .artifactRevisionBindings(java.util.Map.of())
                .build());
        when(artifactBundleService.getBundles()).thenReturn(bundles);
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-6")
                        .artifactBundleId("bundle-6")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-04-05T11:30:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertNull(tacticQualityMetricsService.enrich(null));
        assertNull(enriched.getSuccessRate());
        assertNull(enriched.getBenchmarkWinRate());
        assertNull(enriched.getGolemLocalUsageSuccess());
        assertNull(enriched.getRecencyScore());
    }

    @Test
    void shouldEnrichAllRecordsWithSingleScanOfBundlesAndRuns() {
        TacticRecord tacticA = TacticRecord.builder()
                .tacticId("tactic-a")
                .artifactStreamId("stream-a")
                .contentRevisionId("revision-a")
                .updatedAt(Instant.parse("2026-04-01T00:00:00Z"))
                .build();
        TacticRecord tacticB = TacticRecord.builder()
                .tacticId("tactic-b")
                .artifactStreamId("stream-b")
                .contentRevisionId("revision-b")
                .updatedAt(Instant.parse("2026-03-01T00:00:00Z"))
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-a")
                        .artifactRevisionBindings(java.util.Map.of("stream-a", "revision-a"))
                        .activatedAt(Instant.parse("2026-04-05T09:00:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-b")
                        .artifactRevisionBindings(java.util.Map.of("stream-b", "revision-b"))
                        .activatedAt(Instant.parse("2026-03-02T09:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-a1")
                        .artifactBundleId("bundle-a")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build(),
                RunRecord.builder()
                        .id("run-a2")
                        .artifactBundleId("bundle-a")
                        .status("FAILED")
                        .completedAt(Instant.parse("2026-04-05T10:30:00Z"))
                        .build(),
                RunRecord.builder()
                        .id("run-b1")
                        .artifactBundleId("bundle-b")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-03-02T10:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.findVerdict("run-a1")).thenReturn(Optional.empty());
        when(selfEvolvingRunService.findVerdict("run-a2")).thenReturn(Optional.empty());
        when(selfEvolvingRunService.findVerdict("run-b1")).thenReturn(Optional.empty());

        List<TacticRecord> enriched = tacticQualityMetricsService.enrichAll(List.of(tacticA, tacticB));

        assertEquals(2, enriched.size());
        assertEquals(0.5d, enriched.get(0).getSuccessRate());
        assertEquals(0.5d, enriched.get(0).getGolemLocalUsageSuccess());
        assertEquals(1.0d, enriched.get(0).getRecencyScore());
        assertEquals(1.0d, enriched.get(1).getSuccessRate());
        assertEquals(1.0d, enriched.get(1).getGolemLocalUsageSuccess());
        // tactic-b was last observed on 2026-03-02, clock is 2026-04-05 → 34 days → 0.0
        assertEquals(0.0d, enriched.get(1).getRecencyScore());
        // One scan each: bundle list once, run list once (not per-tactic).
        verify(artifactBundleService, times(1)).getBundles();
        verify(selfEvolvingRunService, times(1)).getRuns();
    }

    @Test
    void shouldDeriveBenchmarkWinRateFromCampaignVerdictsWhereCandidateDiffersFromBaseline() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-bench")
                .artifactStreamId("stream-bench")
                .contentRevisionId("revision-candidate")
                .build();
        ArtifactBundleRecord candidateBundle = ArtifactBundleRecord.builder()
                .id("bundle-candidate")
                .artifactRevisionBindings(java.util.Map.of("stream-bench", "revision-candidate"))
                .build();
        ArtifactBundleRecord baselineBundle = ArtifactBundleRecord.builder()
                .id("bundle-baseline")
                .artifactRevisionBindings(java.util.Map.of("stream-bench", "revision-baseline"))
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(candidateBundle, baselineBundle));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of(
                BenchmarkCampaign.builder()
                        .id("campaign-win")
                        .candidateBundleId("bundle-candidate")
                        .baselineBundleId("bundle-baseline")
                        .build(),
                BenchmarkCampaign.builder()
                        .id("campaign-loss")
                        .candidateBundleId("bundle-candidate")
                        .baselineBundleId("bundle-baseline")
                        .build(),
                BenchmarkCampaign.builder()
                        .id("campaign-no-verdict")
                        .candidateBundleId("bundle-candidate")
                        .baselineBundleId("bundle-baseline")
                        .build()));
        when(benchmarkLabService.findVerdictByCampaignId("campaign-win"))
                .thenReturn(Optional.of(BenchmarkCampaignVerdict.builder()
                        .campaignId("campaign-win")
                        .recommendation("promote")
                        .build()));
        when(benchmarkLabService.findVerdictByCampaignId("campaign-loss"))
                .thenReturn(Optional.of(BenchmarkCampaignVerdict.builder()
                        .campaignId("campaign-loss")
                        .recommendation("reject")
                        .qualityDelta(-0.2d)
                        .build()));
        when(benchmarkLabService.findVerdictByCampaignId("campaign-no-verdict")).thenReturn(Optional.empty());

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertEquals(0.5d, enriched.getBenchmarkWinRate());
    }

    @Test
    void shouldNotAttributeCampaignToTacticWhenBaselineAndCandidateRevisionsMatch() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-bench-noop")
                .artifactStreamId("stream-noop")
                .contentRevisionId("revision-same")
                .build();
        ArtifactBundleRecord candidate = ArtifactBundleRecord.builder()
                .id("bundle-same-a")
                .artifactRevisionBindings(java.util.Map.of("stream-noop", "revision-same"))
                .build();
        ArtifactBundleRecord baseline = ArtifactBundleRecord.builder()
                .id("bundle-same-b")
                .artifactRevisionBindings(java.util.Map.of("stream-noop", "revision-same"))
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(candidate, baseline));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of(BenchmarkCampaign.builder()
                .id("campaign-same")
                .candidateBundleId("bundle-same-a")
                .baselineBundleId("bundle-same-b")
                .build()));
        when(benchmarkLabService.findVerdictByCampaignId("campaign-same"))
                .thenReturn(Optional.of(BenchmarkCampaignVerdict.builder()
                        .campaignId("campaign-same")
                        .recommendation("promote")
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertNull(enriched.getBenchmarkWinRate());
    }

    @Test
    void shouldReturnSameListWhenEnrichAllReceivesNullOrEmpty() {
        assertNull(tacticQualityMetricsService.enrichAll(null));
        List<TacticRecord> empty = List.of();
        assertSame(empty, tacticQualityMetricsService.enrichAll(empty));
    }

    @Test
    void shouldAttributeRunToTacticViaAppliedTacticIdsEvenWithoutBundleBindings() {
        // Ordinary runs take a fresh snapshot bundle that has no
        // artifactRevisionBindings, so without the appliedTacticIds path
        // the run would never count toward the tactic's successRate.
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-applied")
                .artifactStreamId("stream-applied")
                .contentRevisionId("revision-applied")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-snapshot")
                        .artifactRevisionBindings(java.util.Map.of())
                        .build()));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-applied-1")
                        .artifactBundleId("bundle-snapshot")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .appliedTacticIds(List.of("tactic-applied"))
                        .build(),
                RunRecord.builder()
                        .id("run-applied-2")
                        .artifactBundleId("bundle-snapshot")
                        .status("FAILED")
                        .completedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .appliedTacticIds(List.of("tactic-applied"))
                        .build()));
        when(selfEvolvingRunService.findVerdict("run-applied-1")).thenReturn(Optional.empty());
        when(selfEvolvingRunService.findVerdict("run-applied-2")).thenReturn(Optional.empty());

        TacticRecord enriched = tacticQualityMetricsService.enrichAll(List.of(tactic)).getFirst();

        assertEquals(0.5d, enriched.getSuccessRate());
        assertEquals(0.5d, enriched.getGolemLocalUsageSuccess());
        assertEquals(1.0d, enriched.getRecencyScore());
    }

    @Test
    void shouldAttributeJournalOutcomesToMatchingTacticSuccessRate() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-journal")
                .artifactStreamId("stream-journal")
                .contentRevisionId("revision-journal")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of());
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of(
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-journal")
                        .finishReason("success")
                        .recordedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build(),
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-journal")
                        .finishReason("success")
                        .recordedAt(Instant.parse("2026-04-05T10:30:00Z"))
                        .build(),
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-journal")
                        .finishReason("error")
                        .recordedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        // 2 success + 1 error = 2/3 ≈ 0.6667
        assertEquals(2.0d / 3.0d, enriched.getSuccessRate(), 0.001d);
        assertEquals(1.0d, enriched.getRecencyScore());
    }

    @Test
    void shouldMapIterationLimitAndDeadlineAsFailedOutcomes() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-fail-modes")
                .artifactStreamId("stream-fm")
                .contentRevisionId("revision-fm")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of());
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of(
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-fail-modes")
                        .finishReason("iteration_limit")
                        .recordedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build(),
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-fail-modes")
                        .finishReason("deadline")
                        .recordedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertEquals(0.0d, enriched.getSuccessRate());
        assertTrue(enriched.getRegressionFlags().contains("observed-high-failure-rate"));
        assertTrue(enriched.getRegressionFlags().contains("recent-failure"));
    }

    @Test
    void shouldTreatFailureFinishReasonAsFailedOutcome() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-failure")
                .artifactStreamId("stream-failure")
                .contentRevisionId("revision-failure")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of());
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of(
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-failure")
                        .finishReason("success")
                        .recordedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build(),
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-failure")
                        .finishReason("failure")
                        .recordedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertEquals(0.5d, enriched.getSuccessRate());
        assertTrue(enriched.getRegressionFlags().contains("recent-failure"));
    }

    @Test
    void shouldIgnoreJournalEntriesForUnknownTacticIds() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-known")
                .artifactStreamId("stream-known")
                .contentRevisionId("revision-known")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of());
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of(
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-unknown")
                        .finishReason("success")
                        .recordedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        assertNull(enriched.getSuccessRate());
    }

    @Test
    void shouldIgnoreJournalEntriesWithNonTerminalFinishReasons() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-plan")
                .artifactStreamId("stream-plan")
                .contentRevisionId("revision-plan")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of());
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of(
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-plan")
                        .finishReason("plan_mode")
                        .recordedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build(),
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-plan")
                        .finishReason("policy_denied")
                        .recordedAt(Instant.parse("2026-04-05T11:00:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        // plan_mode and policy_denied are not terminal outcomes — no run outcome
        // recorded
        assertNull(enriched.getSuccessRate());
        // but recency should still be updated from noteObservation
        assertEquals(1.0d, enriched.getRecencyScore());
    }

    @Test
    void shouldCombineRunAndJournalOutcomesIntoSingleSuccessRate() {
        TacticRecord tactic = TacticRecord.builder()
                .tacticId("tactic-combined")
                .artifactStreamId("stream-combined")
                .contentRevisionId("revision-combined")
                .build();
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-combined")
                        .artifactRevisionBindings(java.util.Map.of("stream-combined", "revision-combined"))
                        .activatedAt(Instant.parse("2026-04-05T08:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.getRuns()).thenReturn(List.of(
                RunRecord.builder()
                        .id("run-combined")
                        .artifactBundleId("bundle-combined")
                        .status("COMPLETED")
                        .completedAt(Instant.parse("2026-04-05T09:00:00Z"))
                        .build()));
        when(selfEvolvingRunService.findVerdict("run-combined")).thenReturn(Optional.empty());
        when(tacticOutcomeJournalService.getEntries()).thenReturn(List.of(
                TacticOutcomeEntry.builder()
                        .tacticId("tactic-combined")
                        .finishReason("error")
                        .recordedAt(Instant.parse("2026-04-05T10:00:00Z"))
                        .build()));

        TacticRecord enriched = tacticQualityMetricsService.enrich(tactic);

        // 1 success (run) + 1 failure (journal) = 0.5
        assertEquals(0.5d, enriched.getSuccessRate());
    }
}
