package me.golemcore.bot.domain.service;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.model.selfevolving.RunVerdict;
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
    private SessionPort sessionPort;
    private SelfEvolvingProjectionService projectionService;

    @BeforeEach
    void setUp() {
        runService = mock(SelfEvolvingRunService.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        deterministicJudgeService = mock(DeterministicJudgeService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        sessionPort = mock(SessionPort.class);
        projectionService = new SelfEvolvingProjectionService(
                runService,
                artifactBundleService,
                deterministicJudgeService,
                promotionWorkflowService,
                benchmarkLabService,
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
}
