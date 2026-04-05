package me.golemcore.bot.domain.selfevolving.artifact;

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactLineageProjectionServiceTest {

    private ArtifactLineageProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ArtifactLineageProjectionService(
                Clock.fixed(Instant.parse("2026-03-31T20:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldBuildOrderedLineageRailFromCandidateAndPromotionDecisions() {
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .baseVersion("bundle-base")
                .sourceRunIds(List.of("run-2"))
                .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                .build();
        List<PromotionDecision> decisions = List.of(
                PromotionDecision.builder()
                        .id("decision-2")
                        .artifactStreamId("stream-1")
                        .contentRevisionId("rev-2")
                        .originBundleId("bundle-canary")
                        .toLifecycleState("candidate")
                        .toRolloutStage("canary")
                        .decidedAt(Instant.parse("2026-03-31T19:15:00Z"))
                        .build(),
                PromotionDecision.builder()
                        .id("decision-1")
                        .artifactStreamId("stream-1")
                        .contentRevisionId("rev-2")
                        .originBundleId("bundle-shadow")
                        .toLifecycleState("candidate")
                        .toRolloutStage("shadowed")
                        .decidedAt(Instant.parse("2026-03-31T19:10:00Z"))
                        .build());
        List<BenchmarkCampaign> campaigns = List.of(
                BenchmarkCampaign.builder().id("campaign-base").baselineBundleId("bundle-base").build(),
                BenchmarkCampaign.builder().id("campaign-canary").candidateBundleId("bundle-canary").build());

        ArtifactLineageProjection projection = service.project(
                "stream-1",
                candidate,
                decisions,
                campaigns);

        assertEquals(List.of("candidate-1:proposed", "decision-1:shadowed", "decision-2:canary"),
                projection.getRailOrder());
        assertEquals("shadow_promoted", projection.getEdges().getFirst().getEdgeType());
        assertEquals("canary_promoted", projection.getEdges().get(1).getEdgeType());
        assertEquals(List.of("campaign-base"), projection.getNodes().getFirst().getCampaignIds());
        assertEquals(List.of("campaign-canary"), projection.getNodes().getLast().getCampaignIds());
        assertEquals("decision-2:canary", projection.getDefaultSelectedNodeId());
        assertEquals("rev-2", projection.getDefaultSelectedRevisionId());
    }
}
