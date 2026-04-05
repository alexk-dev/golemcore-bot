package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticQualityMetricsServiceTest {

    private ArtifactBundleService artifactBundleService;
    private SelfEvolvingRunService selfEvolvingRunService;
    private Clock clock;
    private TacticQualityMetricsService tacticQualityMetricsService;

    @BeforeEach
    void setUp() {
        artifactBundleService = mock(ArtifactBundleService.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        clock = Clock.fixed(Instant.parse("2026-04-05T12:00:00Z"), ZoneOffset.UTC);
        tacticQualityMetricsService = new TacticQualityMetricsService(artifactBundleService, selfEvolvingRunService,
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
        java.util.ArrayList<ArtifactBundleRecord> bundles = new java.util.ArrayList<>();
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
}
