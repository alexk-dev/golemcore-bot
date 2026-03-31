package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactImpactServiceTest {

    private ArtifactImpactService artifactImpactService;

    @BeforeEach
    void setUp() {
        artifactImpactService = new ArtifactImpactService();
    }

    @Test
    void shouldMarkImpactAsIsolatedWhenOnlyOneArtifactBindingChanged() {
        ArtifactImpactProjection impact = artifactImpactService.summarizeImpact(
                "stream-1",
                "rev-1",
                "rev-2",
                ArtifactBundleRecord.builder()
                        .id("bundle-1")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1", "stream-2", "rev-a"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-2")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2", "stream-2", "rev-a"))
                        .build(),
                List.of(BenchmarkCampaign.builder()
                        .id("campaign-1")
                        .baselineBundleId("bundle-1")
                        .candidateBundleId("bundle-2")
                        .status("completed")
                        .startedAt(Instant.parse("2026-03-31T20:00:00Z"))
                        .build()),
                Set.of("rev-2"));

        assertEquals("isolated", impact.getAttributionMode());
        assertEquals(1, impact.getCampaignDelta());
        assertTrue(Boolean.TRUE.equals(impact.getRegressionIntroduced()));
    }

    @Test
    void shouldMarkImpactAsMixedChangeWhenMultipleBindingsChanged() {
        ArtifactImpactProjection impact = artifactImpactService.summarizeImpact(
                "stream-1",
                "rev-1",
                "rev-2",
                ArtifactBundleRecord.builder()
                        .id("bundle-1")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1", "stream-2", "rev-a"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-2")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2", "stream-2", "rev-b"))
                        .build(),
                List.of(),
                Set.of());

        assertEquals("mixed_change", impact.getAttributionMode());
        assertEquals(0, impact.getCampaignDelta());
    }
}
