package me.golemcore.bot.domain.selfevolving.artifact;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.candidate.EvolutionCandidateService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;

class ArtifactDiffProjectionServiceTest {

    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService promotionWorkflowService;
    private ArtifactBundleService artifactBundleService;
    private BenchmarkLabService benchmarkLabService;
    private ArtifactDiffProjectionService service;

    @BeforeEach
    void setUp() {
        evolutionCandidateService = mock(EvolutionCandidateService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);

        ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService = new ArtifactNormalizedRevisionProjectionService();
        ArtifactDiffService artifactDiffService = new ArtifactDiffService(normalizedRevisionProjectionService);
        ArtifactImpactService artifactImpactService = new ArtifactImpactService();
        ArtifactProjectionLookupService artifactProjectionLookupService = new ArtifactProjectionLookupService(
                evolutionCandidateService,
                promotionWorkflowService,
                artifactBundleService,
                benchmarkLabService);
        service = new ArtifactDiffProjectionService(
                artifactProjectionLookupService,
                benchmarkLabService,
                normalizedRevisionProjectionService,
                artifactDiffService,
                artifactImpactService);
    }

    @Test
    void shouldBuildRevisionDiffFromRevisionLookupAndImpactData() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(
                revision("rev-1", null, "planner v1", Instant.parse("2026-03-31T18:00:00Z")),
                revision("rev-2", "rev-1", "planner v2", Instant.parse("2026-03-31T19:00:00Z"))));
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-1")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                        .createdAt(Instant.parse("2026-03-31T18:10:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-2")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2"))
                        .createdAt(Instant.parse("2026-03-31T19:10:00Z"))
                        .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of());

        ArtifactRevisionDiffProjection revisionDiff = service.getRevisionDiff("stream-1", "rev-1", "rev-2");

        assertEquals("skill:planner", revisionDiff.getArtifactKey());
        assertEquals("rev-1", revisionDiff.getFromRevisionId());
        assertEquals("rev-2", revisionDiff.getToRevisionId());
        assertFalse(revisionDiff.getChangedFields().isEmpty());
        assertEquals("isolated", revisionDiff.getAttributionMode());
    }

    @Test
    void shouldBuildTransitionDiffUsingResolvedBaseRevisionForRolloutOnlyTransition() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(
                revision("rev-1", null, "planner v1", Instant.parse("2026-03-31T18:00:00Z")),
                revision("rev-2", "rev-1", "planner v2", Instant.parse("2026-03-31T19:00:00Z"))));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .build()));
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(PromotionDecision.builder()
                .id("decision-1")
                .candidateId("candidate-1")
                .artifactStreamId("stream-1")
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .build()));
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-1")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                        .createdAt(Instant.parse("2026-03-31T18:10:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-2")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2"))
                        .createdAt(Instant.parse("2026-03-31T19:10:00Z"))
                        .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of());

        ArtifactTransitionDiffProjection transitionDiff = service.getTransitionDiff(
                "stream-1",
                ArtifactLineageProjection.builder()
                        .artifactStreamId("stream-1")
                        .nodes(List.of(
                                ArtifactLineageNode.builder()
                                        .nodeId("candidate-1:proposed")
                                        .contentRevisionId("rev-2")
                                        .rolloutStage("proposed")
                                        .build(),
                                ArtifactLineageNode.builder()
                                        .nodeId("decision-1:shadowed")
                                        .contentRevisionId("rev-2")
                                        .rolloutStage("shadowed")
                                        .promotionDecisionId("decision-1")
                                        .build()))
                        .build(),
                "candidate-1:proposed",
                "decision-1:shadowed");

        assertEquals("shadowed", transitionDiff.getToRolloutStage());
        assertEquals("rev-2", transitionDiff.getFromRevisionId());
        assertEquals("rev-2", transitionDiff.getToRevisionId());
        assertTrue(transitionDiff.isContentChanged());
        assertEquals("Transition includes content change", transitionDiff.getSummary());
    }

    private ArtifactRevisionRecord revision(
            String revisionId,
            String baseRevisionId,
            String rawContent,
            Instant createdAt) {
        return ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .artifactSubtype("skill")
                .contentRevisionId(revisionId)
                .baseContentRevisionId(baseRevisionId)
                .rawContent(rawContent)
                .sourceRunIds(List.of("run-" + revisionId))
                .createdAt(createdAt)
                .build();
    }
}
