package me.golemcore.bot.domain.service;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCampaignDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCompareOptionsDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactRevisionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactTransitionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactWorkspaceSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingProjectionServiceTest {

    private SelfEvolvingRunService runService;
    private ArtifactBundleService artifactBundleService;
    private DeterministicJudgeService deterministicJudgeService;
    private PromotionWorkflowService promotionWorkflowService;
    private BenchmarkLabService benchmarkLabService;
    private ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService;
    private SessionPort sessionPort;
    private SelfEvolvingProjectionService projectionService;

    @BeforeEach
    void setUp() {
        runService = mock(SelfEvolvingRunService.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        deterministicJudgeService = mock(DeterministicJudgeService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        artifactWorkspaceProjectionService = mock(ArtifactWorkspaceProjectionService.class);
        sessionPort = mock(SessionPort.class);
        projectionService = new SelfEvolvingProjectionService(
                runService,
                artifactBundleService,
                deterministicJudgeService,
                promotionWorkflowService,
                benchmarkLabService,
                artifactWorkspaceProjectionService,
                sessionPort);
    }

    @Test
    void shouldProjectRunsForDashboardList() {
        RunRecord run = RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .sessionId("session-1")
                .traceId("trace-1")
                .artifactBundleId("bundle-1")
                .status("COMPLETED")
                .startedAt(Instant.parse("2026-03-31T14:00:00Z"))
                .completedAt(Instant.parse("2026-03-31T14:00:05Z"))
                .build();
        TraceRecord trace = TraceRecord.builder()
                .traceId("trace-1")
                .build();
        when(runService.getRuns()).thenReturn(List.of(run));
        when(sessionPort.get("session-1")).thenReturn(Optional.of(AgentSession.builder()
                .id("session-1")
                .traces(List.of(trace))
                .metadata(Map.of())
                .build()));
        when(deterministicJudgeService.evaluate(run, trace)).thenReturn(RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("COMPLETED")
                .promotionRecommendation("approve_gated")
                .build());

        List<SelfEvolvingRunSummaryDto> runs = projectionService.listRuns();

        assertEquals(1, runs.size());
        assertEquals("run-1", runs.getFirst().getId());
        assertEquals("COMPLETED", runs.getFirst().getOutcomeStatus());
        assertEquals("approve_gated", runs.getFirst().getPromotionRecommendation());
    }

    @Test
    void shouldProjectRunDetailWithVerdictAndBundle() {
        RunRecord run = RunRecord.builder()
                .id("run-1")
                .golemId("golem-1")
                .sessionId("session-1")
                .traceId("trace-1")
                .artifactBundleId("bundle-1")
                .status("FAILED")
                .build();
        ArtifactBundleRecord bundle = ArtifactBundleRecord.builder()
                .id("bundle-1")
                .golemId("golem-1")
                .status("SNAPSHOT")
                .build();
        TraceRecord trace = TraceRecord.builder()
                .traceId("trace-1")
                .build();
        RunVerdict verdict = RunVerdict.builder()
                .runId("run-1")
                .outcomeStatus("FAILED")
                .promotionRecommendation("reject")
                .processFindings(List.of("tool_error"))
                .build();
        when(runService.getRuns()).thenReturn(List.of(run));
        when(artifactBundleService.getBundles()).thenReturn(List.of(bundle));
        when(sessionPort.get("session-1")).thenReturn(Optional.of(AgentSession.builder()
                .id("session-1")
                .traces(List.of(trace))
                .metadata(Map.of())
                .build()));
        when(deterministicJudgeService.evaluate(run, trace)).thenReturn(verdict);

        Optional<SelfEvolvingRunDetailDto> detail = projectionService.getRun("run-1");

        assertTrue(detail.isPresent());
        assertEquals("bundle-1", detail.get().getArtifactBundleId());
        assertEquals("FAILED", detail.get().getVerdict().getOutcomeStatus());
        assertFalse(detail.get().getVerdict().getProcessFindings().isEmpty());
    }

    @Test
    void shouldProjectCandidateQueueFromPromotionWorkflow() {
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .goal("fix")
                .artifactType("tool_policy")
                .status("approved_pending")
                .sourceRunIds(List.of("run-1"))
                .build()));

        assertEquals(1, projectionService.listCandidates().size());
        assertEquals("candidate-1", projectionService.listCandidates().getFirst().getId());
    }

    @Test
    void shouldFilterArtifactCatalogByLifecycleRegressionBenchmarkAndQuery() {
        when(artifactWorkspaceProjectionService.listCatalog()).thenReturn(List.of(
                ArtifactCatalogEntry.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .artifactAliases(List.of("planner", "strategy"))
                        .artifactType("skill")
                        .artifactSubtype("skill")
                        .displayName("Planner")
                        .currentLifecycleState("candidate")
                        .currentRolloutStage("canary")
                        .hasPendingApproval(true)
                        .hasRegression(true)
                        .campaignCount(2)
                        .build(),
                ArtifactCatalogEntry.builder()
                        .artifactStreamId("stream-2")
                        .artifactKey("prompt:toolloop-core")
                        .artifactAliases(List.of("toolloop"))
                        .artifactType("prompt")
                        .artifactSubtype("prompt")
                        .displayName("Toolloop Core")
                        .currentLifecycleState("active")
                        .currentRolloutStage("active")
                        .hasPendingApproval(false)
                        .hasRegression(false)
                        .campaignCount(0)
                        .build()));

        List<SelfEvolvingArtifactCatalogEntryDto> artifacts = projectionService.listArtifacts(
                "skill",
                "skill",
                "candidate",
                "canary",
                true,
                true,
                true,
                "strategy");

        assertEquals(1, artifacts.size());
        assertEquals("stream-1", artifacts.getFirst().getArtifactStreamId());
        assertEquals("Planner", artifacts.getFirst().getDisplayName());
    }

    @Test
    void shouldBuildWorkspaceSummaryCompareOptionsAndArtifactDtos() {
        ArtifactCatalogEntry entry = ArtifactCatalogEntry.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("origin-1")
                .artifactKey("skill:planner")
                .artifactAliases(List.of("planner"))
                .artifactType("skill")
                .artifactSubtype("skill")
                .activeRevisionId("rev-1")
                .latestCandidateRevisionId("rev-3")
                .currentLifecycleState("candidate")
                .currentRolloutStage("shadowed")
                .campaignCount(3)
                .updatedAt(Instant.parse("2026-03-31T19:00:00Z"))
                .projectedAt(Instant.parse("2026-03-31T19:30:00Z"))
                .build();
        when(artifactWorkspaceProjectionService.listCatalog()).thenReturn(List.of(entry));
        when(artifactWorkspaceProjectionService.listRevisions("stream-1")).thenReturn(List.of(
                ArtifactRevisionProjection.builder().contentRevisionId("rev-1").build(),
                ArtifactRevisionProjection.builder().contentRevisionId("rev-2").build(),
                ArtifactRevisionProjection.builder().contentRevisionId("rev-3").build()));
        when(artifactWorkspaceProjectionService.getLineage("stream-1")).thenReturn(ArtifactLineageProjection.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("origin-1")
                .artifactKey("skill:planner")
                .railOrder(List.of("candidate-1:proposed", "decision-1:shadowed"))
                .defaultSelectedNodeId("decision-1:shadowed")
                .defaultSelectedRevisionId("rev-3")
                .build());

        Optional<SelfEvolvingArtifactWorkspaceSummaryDto> summary = projectionService
                .getArtifactWorkspaceSummary("stream-1");
        Optional<SelfEvolvingArtifactCompareOptionsDto> compareOptions = projectionService
                .getArtifactCompareOptions("stream-1");
        Optional<SelfEvolvingArtifactLineageDto> lineage = projectionService.getArtifactLineage("stream-1");

        assertTrue(summary.isPresent());
        assertEquals("rev-1", summary.get().getCompareOptions().getDefaultFromRevisionId());
        assertEquals("rev-3", summary.get().getCompareOptions().getDefaultToRevisionId());
        assertTrue(compareOptions.isPresent());
        assertFalse(compareOptions.get().getRevisionOptions().isEmpty());
        assertTrue(lineage.isPresent());
        assertEquals("decision-1:shadowed", lineage.get().getDefaultSelectedNodeId());
    }

    @Test
    void shouldProjectArtifactDiffsEvidenceAndCampaigns() {
        ArtifactCatalogEntry entry = ArtifactCatalogEntry.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .activeRevisionId("rev-1")
                .latestCandidateRevisionId("rev-2")
                .build();
        when(artifactWorkspaceProjectionService.listCatalog()).thenReturn(List.of(entry));
        when(artifactWorkspaceProjectionService.listRevisions("stream-1")).thenReturn(List.of(
                ArtifactRevisionProjection.builder().contentRevisionId("rev-1").build(),
                ArtifactRevisionProjection.builder().contentRevisionId("rev-2").build()));
        when(artifactWorkspaceProjectionService.getLineage("stream-1")).thenReturn(ArtifactLineageProjection.builder()
                .artifactStreamId("stream-1")
                .railOrder(List.of("node-1", "node-2"))
                .build());
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of(BenchmarkCampaign.builder()
                .id("campaign-1")
                .status("created")
                .runIds(List.of("run-1"))
                .build()));
        when(artifactWorkspaceProjectionService.getRevisionDiff("stream-1", "rev-1", "rev-2"))
                .thenReturn(ArtifactRevisionDiffProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .summary("rev diff")
                        .semanticSections(List.of("body"))
                        .changedFields(List.of("rawContent"))
                        .riskSignals(List.of("regression"))
                        .build());
        when(artifactWorkspaceProjectionService.getTransitionDiff("stream-1", "node-1", "node-2"))
                .thenReturn(ArtifactTransitionDiffProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .fromNodeId("node-1")
                        .toNodeId("node-2")
                        .fromRolloutStage("candidate")
                        .toRolloutStage("shadowed")
                        .contentChanged(true)
                        .summary("transition diff")
                        .build());
        when(artifactWorkspaceProjectionService.getRevisionEvidence("stream-1", "rev-2"))
                .thenReturn(ArtifactRevisionEvidenceProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .revisionId("rev-2")
                        .runIds(List.of("run-2"))
                        .traceIds(List.of("trace-2"))
                        .findings(List.of("revision finding"))
                        .build());
        when(artifactWorkspaceProjectionService.getCompareEvidence("stream-1", "rev-1", "rev-2"))
                .thenReturn(ArtifactCompareEvidenceProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .campaignIds(List.of("campaign-1"))
                        .findings(List.of("compare finding"))
                        .build());
        when(artifactWorkspaceProjectionService.getTransitionEvidence("stream-1", "node-1", "node-2"))
                .thenReturn(ArtifactTransitionEvidenceProjection.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .fromNodeId("node-1")
                        .toNodeId("node-2")
                        .fromRevisionId("rev-1")
                        .toRevisionId("rev-2")
                        .promotionDecisionIds(List.of("decision-1"))
                        .findings(List.of("transition finding"))
                        .build());

        List<SelfEvolvingCampaignDto> campaigns = projectionService.listCampaigns();
        Optional<SelfEvolvingArtifactRevisionDiffDto> revisionDiff = projectionService.getArtifactRevisionDiff(
                "stream-1",
                "rev-1",
                "rev-2");
        Optional<SelfEvolvingArtifactTransitionDiffDto> transitionDiff = projectionService.getArtifactTransitionDiff(
                "stream-1",
                "node-1",
                "node-2");
        Optional<SelfEvolvingArtifactEvidenceDto> revisionEvidence = projectionService.getArtifactRevisionEvidence(
                "stream-1",
                "rev-2");
        Optional<SelfEvolvingArtifactEvidenceDto> compareEvidence = projectionService.getArtifactCompareEvidence(
                "stream-1",
                "rev-1",
                "rev-2");
        Optional<SelfEvolvingArtifactEvidenceDto> transitionEvidence = projectionService.getArtifactTransitionEvidence(
                "stream-1",
                "node-1",
                "node-2");

        assertEquals("campaign-1", campaigns.getFirst().getId());
        assertTrue(revisionDiff.isPresent());
        assertEquals(List.of("rawContent"), revisionDiff.get().getChangedFields());
        assertTrue(transitionDiff.isPresent());
        assertEquals("shadowed", transitionDiff.get().getToRolloutStage());
        assertTrue(revisionEvidence.isPresent());
        assertEquals("revision", revisionEvidence.get().getPayloadKind());
        assertTrue(compareEvidence.isPresent());
        assertEquals("compare", compareEvidence.get().getPayloadKind());
        assertTrue(transitionEvidence.isPresent());
        assertEquals("transition", transitionEvidence.get().getPayloadKind());
    }

    @Test
    void shouldUseFirstSessionTraceWhenRunTraceIdIsMissing() {
        RunRecord run = RunRecord.builder()
                .id("run-2")
                .golemId("golem-1")
                .sessionId("session-2")
                .artifactBundleId("bundle-2")
                .status("COMPLETED")
                .build();
        TraceRecord trace = TraceRecord.builder().traceId("trace-fallback").build();
        when(runService.getRuns()).thenReturn(List.of(run));
        when(sessionPort.get("session-2")).thenReturn(Optional.of(AgentSession.builder()
                .id("session-2")
                .traces(List.of(trace))
                .metadata(Map.of())
                .build()));
        when(deterministicJudgeService.evaluate(run, trace)).thenReturn(RunVerdict.builder()
                .runId("run-2")
                .outcomeStatus("COMPLETED")
                .build());

        Optional<SelfEvolvingRunDetailDto> detail = projectionService.getRun("run-2");

        assertTrue(detail.isPresent());
        assertEquals("COMPLETED", detail.get().getVerdict().getOutcomeStatus());
    }

    @Test
    void shouldReturnEmptyWhenArtifactStreamIsMissing() {
        when(artifactWorkspaceProjectionService.listCatalog()).thenReturn(List.of());

        assertTrue(projectionService.getArtifactWorkspaceSummary("missing").isEmpty());
        assertTrue(projectionService.getArtifactRevisionDiff("missing", "a", "b").isEmpty());
        assertTrue(projectionService.getArtifactTransitionDiff("missing", "a", "b").isEmpty());
        assertTrue(projectionService.getArtifactRevisionEvidence("missing", "a").isEmpty());
        assertTrue(projectionService.getArtifactCompareEvidence("missing", "a", "b").isEmpty());
        assertTrue(projectionService.getArtifactTransitionEvidence("missing", "a", "b").isEmpty());
        assertTrue(projectionService.getArtifactCompareOptions("missing").isEmpty());
    }
}
