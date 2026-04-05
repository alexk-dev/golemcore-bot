package me.golemcore.bot.domain.selfevolving.artifact;

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactDiffServiceTest {

    private ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService;
    private ArtifactDiffService artifactDiffService;

    @BeforeEach
    void setUp() {
        normalizedRevisionProjectionService = new ArtifactNormalizedRevisionProjectionService();
        artifactDiffService = new ArtifactDiffService(normalizedRevisionProjectionService);
    }

    @Test
    void shouldBuildSemanticRevisionDiffFromNormalizedContent() {
        ArtifactNormalizedRevisionProjection fromProjection = normalizedRevisionProjectionService.normalize(
                revision("rev-1", "title: planner\nstep: inspect\nstep: answer"));
        ArtifactNormalizedRevisionProjection toProjection = normalizedRevisionProjectionService.normalize(
                revision("rev-2", "title: planner\nstep: inspect\nstep: synthesize"));

        ArtifactRevisionDiffProjection diff = artifactDiffService.compareRevisions(
                "stream-1",
                "skill:planner",
                fromProjection,
                toProjection,
                ArtifactImpactProjection.builder().attributionMode("isolated").build());

        assertEquals("rev-1", diff.getFromRevisionId());
        assertEquals("rev-2", diff.getToRevisionId());
        assertEquals("isolated", diff.getAttributionMode());
        assertFalse(diff.getChangedFields().isEmpty());
        assertFalse(diff.getSemanticSections().isEmpty());
        assertTrue(diff.getRawPatch().contains("synthesize"));
    }

    @Test
    void shouldMarkTransitionDiffAsContentUnchangedWhenNodesShareRevision() {
        ArtifactTransitionDiffProjection diff = artifactDiffService.compareTransition(
                "stream-1",
                "skill:planner",
                ArtifactLineageNode.builder()
                        .nodeId("node-proposed")
                        .contentRevisionId("rev-2")
                        .rolloutStage("proposed")
                        .build(),
                ArtifactLineageNode.builder()
                        .nodeId("node-shadowed")
                        .contentRevisionId("rev-2")
                        .rolloutStage("shadowed")
                        .build(),
                null,
                ArtifactImpactProjection.builder().attributionMode("bundle_observed").build());

        assertFalse(diff.isContentChanged());
        assertEquals("proposed", diff.getFromRolloutStage());
        assertEquals("shadowed", diff.getToRolloutStage());
        assertEquals("bundle_observed", diff.getAttributionMode());
    }

    @Test
    void shouldMarkRevisionDiffAsMissingWhenEitherSideIsAbsent() {
        ArtifactNormalizedRevisionProjection toProjection = normalizedRevisionProjectionService.normalize(
                revision("rev-2", "title: planner\nstep: inspect"));

        ArtifactRevisionDiffProjection diff = artifactDiffService.compareRevisions(
                "stream-1",
                "skill:planner",
                null,
                toProjection,
                null);

        assertEquals(List.of("missing_revision"), diff.getChangedFields());
        assertEquals("Artifact content changed", diff.getSummary());
        assertEquals(List.of("content_changed"), diff.getRiskSignals());
        assertEquals("rev-2", diff.getToRevisionId());
    }

    @Test
    void shouldDescribeNoContentChangesWhenNormalizedHashesMatch() {
        ArtifactNormalizedRevisionProjection fromProjection = normalizedRevisionProjectionService.normalize(
                revision("rev-1", "title: planner\nstep: inspect"));
        ArtifactNormalizedRevisionProjection toProjection = normalizedRevisionProjectionService.normalize(
                revision("rev-2", "title: planner\nstep: inspect"));

        ArtifactRevisionDiffProjection diff = artifactDiffService.compareRevisions(
                "stream-1",
                "skill:planner",
                fromProjection,
                toProjection,
                ArtifactImpactProjection.builder().attributionMode("isolated").build());

        assertTrue(diff.getChangedFields().isEmpty());
        assertEquals("No content changes", diff.getSummary());
        assertTrue(diff.getRiskSignals().isEmpty());
        assertEquals("isolated", diff.getAttributionMode());
    }

    @Test
    void shouldMarkTransitionDiffAsContentChangedWhenRevisionDiffChangesFields() {
        ArtifactRevisionDiffProjection revisionDiff = ArtifactRevisionDiffProjection.builder()
                .changedFields(List.of("normalizedContent"))
                .build();

        ArtifactTransitionDiffProjection diff = artifactDiffService.compareTransition(
                "stream-1",
                "skill:planner",
                ArtifactLineageNode.builder()
                        .nodeId("node-approved")
                        .contentRevisionId("rev-1")
                        .rolloutStage("approved")
                        .build(),
                ArtifactLineageNode.builder()
                        .nodeId("node-active")
                        .contentRevisionId("rev-2")
                        .rolloutStage("active")
                        .build(),
                revisionDiff,
                null);

        assertTrue(diff.isContentChanged());
        assertEquals("Transition includes content change", diff.getSummary());
        assertEquals("approved", diff.getFromRolloutStage());
        assertEquals("active", diff.getToRolloutStage());
    }

    private ArtifactRevisionRecord revision(String revisionId, String rawContent) {
        return ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .artifactSubtype("skill")
                .contentRevisionId(revisionId)
                .rawContent(rawContent)
                .sourceRunIds(List.of("run-" + revisionId))
                .createdAt(Instant.parse("2026-03-31T20:00:00Z"))
                .build();
    }
}
