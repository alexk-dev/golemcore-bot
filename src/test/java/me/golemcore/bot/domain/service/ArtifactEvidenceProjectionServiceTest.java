package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactEvidenceProjectionServiceTest {

    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService promotionWorkflowService;
    private ArtifactBundleService artifactBundleService;
    private BenchmarkLabService benchmarkLabService;
    private ArtifactEvidenceProjectionService service;

    @BeforeEach
    void setUp() {
        evolutionCandidateService = mock(EvolutionCandidateService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        service = new ArtifactEvidenceProjectionService(
                evolutionCandidateService,
                promotionWorkflowService,
                artifactBundleService,
                benchmarkLabService,
                Clock.fixed(Instant.parse("2026-03-31T20:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldBuildRevisionEvidenceFromRevisionCandidateAndPromotionSources() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .contentRevisionId("rev-2")
                .sourceRunIds(List.of("run-revision"))
                .traceIds(List.of("trace-revision"))
                .spanIds(List.of("span-revision"))
                .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                .build()));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .sourceRunIds(List.of("run-candidate"))
                .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                        .traceId("trace-candidate")
                        .spanId("span-candidate")
                        .build()))
                .build()));
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(PromotionDecision.builder()
                .id("decision-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .approvalRequestId("approval-1")
                .build()));
        when(artifactBundleService.getBundles()).thenReturn(List.of(ArtifactBundleRecord.builder()
                .id("bundle-2")
                .artifactRevisionBindings(Map.of("stream-1", "rev-2"))
                .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of(BenchmarkCampaign.builder()
                .id("campaign-2")
                .candidateBundleId("bundle-2")
                .build()));

        ArtifactRevisionEvidenceProjection evidence = service.getRevisionEvidence("stream-1", "rev-2");

        assertEquals(List.of("run-candidate"), evidence.getRunIds());
        assertEquals(List.of("trace-revision", "trace-candidate"), evidence.getTraceIds());
        assertEquals(List.of("span-revision", "span-candidate"), evidence.getSpanIds());
        assertEquals(List.of("campaign-2"), evidence.getCampaignIds());
        assertEquals(List.of("decision-1"), evidence.getPromotionDecisionIds());
        assertEquals(List.of("approval-1"), evidence.getApprovalRequestIds());
    }

    @Test
    void shouldBuildTransitionEvidenceFromLineageNodesAndDecisionLinks() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .contentRevisionId("rev-2")
                .traceIds(List.of("trace-revision"))
                .spanIds(List.of("span-revision"))
                .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                .build()));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                        .traceId("trace-candidate")
                        .spanId("span-candidate")
                        .build()))
                .build()));
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(PromotionDecision.builder()
                .id("decision-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .approvalRequestId("approval-1")
                .build()));

        ArtifactLineageProjection lineage = ArtifactLineageProjection.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .nodes(List.of(
                        ArtifactLineageNode.builder()
                                .nodeId("candidate-1:proposed")
                                .contentRevisionId("rev-2")
                                .sourceRunIds(List.of("run-proposed"))
                                .campaignIds(List.of("campaign-base"))
                                .build(),
                        ArtifactLineageNode.builder()
                                .nodeId("decision-1:shadowed")
                                .contentRevisionId("rev-2")
                                .promotionDecisionId("decision-1")
                                .sourceRunIds(List.of("run-shadowed"))
                                .campaignIds(List.of("campaign-shadow"))
                                .build()))
                .build();

        ArtifactTransitionEvidenceProjection evidence = service.getTransitionEvidence(
                "stream-1",
                lineage,
                "candidate-1:proposed",
                "decision-1:shadowed");

        assertEquals("candidate-1:proposed", evidence.getFromNodeId());
        assertEquals("decision-1:shadowed", evidence.getToNodeId());
        assertEquals(List.of("run-proposed", "run-shadowed"), evidence.getRunIds());
        assertEquals(List.of("trace-revision", "trace-candidate"), evidence.getTraceIds());
        assertEquals(List.of("span-revision", "span-candidate"), evidence.getSpanIds());
        assertEquals(List.of("campaign-base", "campaign-shadow"), evidence.getCampaignIds());
        assertEquals(List.of("decision-1"), evidence.getPromotionDecisionIds());
        assertEquals(List.of("approval-1"), evidence.getApprovalRequestIds());
    }
}
