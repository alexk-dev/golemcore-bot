package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactProjectionLookupServiceTest {

    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService promotionWorkflowService;
    private ArtifactBundleService artifactBundleService;
    private BenchmarkLabService benchmarkLabService;
    private ArtifactProjectionLookupService service;

    @BeforeEach
    void setUp() {
        evolutionCandidateService = mock(EvolutionCandidateService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        service = new ArtifactProjectionLookupService(
                evolutionCandidateService,
                promotionWorkflowService,
                artifactBundleService,
                benchmarkLabService);
    }

    @Test
    void shouldResolveLatestCandidateRevisionLookupAndCandidateRunIds() {
        ArtifactRevisionRecord revision = ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .contentRevisionId("rev-1")
                .sourceRunIds(List.of("run-revision"))
                .createdAt(Instant.parse("2026-03-31T18:00:00Z"))
                .build();
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(revision));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(
                EvolutionCandidate.builder()
                        .id("candidate-old")
                        .artifactStreamId("stream-1")
                        .contentRevisionId("rev-1")
                        .sourceRunIds(List.of("run-old"))
                        .createdAt(Instant.parse("2026-03-31T18:05:00Z"))
                        .build(),
                EvolutionCandidate.builder()
                        .id("candidate-new")
                        .artifactStreamId("stream-1")
                        .contentRevisionId("rev-1")
                        .sourceRunIds(List.of("run-new"))
                        .createdAt(Instant.parse("2026-03-31T18:10:00Z"))
                        .build()));

        assertEquals("candidate-new", service.findLatestCandidate("stream-1").orElseThrow().getId());
        assertTrue(service.findRevision("stream-1", "rev-1").isPresent());
        assertEquals(List.of("run-old", "run-new"), service.resolveRunIdsForRevision("stream-1", revision));
    }

    @Test
    void shouldResolveActiveRevisionAndCampaignCountFromBundles() {
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-snapshot")
                        .status("snapshot")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                        .createdAt(Instant.parse("2026-03-31T18:00:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-active")
                        .status("active")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2"))
                        .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-other")
                        .status("active")
                        .artifactRevisionBindings(Map.of("stream-2", "rev-x"))
                        .createdAt(Instant.parse("2026-03-31T20:00:00Z"))
                        .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of(
                BenchmarkCampaign.builder().id("campaign-1").baselineBundleId("bundle-snapshot").build(),
                BenchmarkCampaign.builder().id("campaign-2").candidateBundleId("bundle-active").build(),
                BenchmarkCampaign.builder().id("campaign-3").candidateBundleId("bundle-other").build()));

        assertEquals("rev-2", service.resolveActiveRevisionId("stream-1").orElseThrow());
        assertEquals(2, service.resolveCampaignCount("stream-1"));
    }

    @Test
    void shouldResolveTransitionBaseRevisionFromDecisionThenCandidateFallback() {
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(
                PromotionDecision.builder()
                        .id("decision-1")
                        .baseContentRevisionId("rev-from-decision")
                        .build()));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(
                EvolutionCandidate.builder()
                        .id("candidate-1")
                        .baseContentRevisionId("rev-from-candidate")
                        .build()));

        String decisionBase = service.resolveTransitionBaseRevisionId(ArtifactLineageNode.builder()
                .nodeId("decision-1:shadowed")
                .promotionDecisionId("decision-1")
                .build());
        String candidateBase = service.resolveTransitionBaseRevisionId(ArtifactLineageNode.builder()
                .nodeId("candidate-1:proposed")
                .build());

        assertEquals("rev-from-decision", decisionBase);
        assertEquals("rev-from-candidate", candidateBase);
    }
}
