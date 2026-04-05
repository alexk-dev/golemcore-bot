package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactWorkspaceProjectionServiceTest {

    private EvolutionCandidateService evolutionCandidateService;
    private PromotionWorkflowService promotionWorkflowService;
    private ArtifactBundleService artifactBundleService;
    private BenchmarkLabService benchmarkLabService;
    private ArtifactWorkspaceProjectionService service;

    @BeforeEach
    void setUp() {
        StoragePort storagePort = mock(StoragePort.class);
        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        evolutionCandidateService = mock(EvolutionCandidateService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        artifactBundleService = mock(ArtifactBundleService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);

        ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService = new ArtifactNormalizedRevisionProjectionService();
        ArtifactDiffService artifactDiffService = new ArtifactDiffService(normalizedRevisionProjectionService);
        ArtifactImpactService artifactImpactService = new ArtifactImpactService();
        service = new ArtifactWorkspaceProjectionService(
                evolutionCandidateService,
                promotionWorkflowService,
                artifactBundleService,
                benchmarkLabService,
                normalizedRevisionProjectionService,
                artifactDiffService,
                artifactImpactService,
                Clock.fixed(Instant.parse("2026-03-31T20:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldMaterializeCandidateRolloutNodesWithoutDuplicatingContentRevisions() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(
                revision("rev-1", null, "planner v1", Instant.parse("2026-03-31T18:00:00Z")),
                revision("rev-2", "rev-1", "planner v2", Instant.parse("2026-03-31T19:00:00Z"))));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactType("skill")
                .artifactSubtype("skill")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactAliases(List.of("skill:planner"))
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .status("canary")
                .lifecycleState("candidate")
                .rolloutStage("canary")
                .sourceRunIds(List.of("run-2"))
                .evidenceRefs(List.of(VerdictEvidenceRef.builder()
                        .traceId("trace-2")
                        .spanId("span-2")
                        .build()))
                .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                .build()));
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(
                decision("decision-1", "replayed", "candidate", "replayed", Instant.parse("2026-03-31T19:05:00Z")),
                decision("decision-2", "shadowed", "candidate", "shadowed", Instant.parse("2026-03-31T19:10:00Z")),
                decision("decision-3", "canary", "candidate", "canary", Instant.parse("2026-03-31T19:15:00Z"))));
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-1")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                        .artifactSubtypeBindings(Map.of("skill:planner", "skill"))
                        .createdAt(Instant.parse("2026-03-31T18:10:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-2")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2"))
                        .artifactSubtypeBindings(Map.of("skill:planner", "skill"))
                        .createdAt(Instant.parse("2026-03-31T19:10:00Z"))
                        .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of(BenchmarkCampaign.builder()
                .id("campaign-1")
                .baselineBundleId("bundle-1")
                .candidateBundleId("bundle-2")
                .status("created")
                .runIds(List.of("run-2"))
                .build()));

        ArtifactLineageProjection lineage = service.getLineage("stream-1");
        ArtifactCatalogEntry catalogEntry = service.listCatalog().getFirst();
        ArtifactRevisionEvidenceProjection evidence = service.getRevisionEvidence("stream-1", "rev-2");

        List<String> candidateStages = lineage.getNodes().stream()
                .map(node -> node.getRolloutStage())
                .filter(stage -> List.of("proposed", "replayed", "shadowed", "canary").contains(stage))
                .toList();

        assertTrue(candidateStages.containsAll(List.of("proposed", "replayed", "shadowed", "canary")));
        assertEquals(List.of("rev-2"), lineage.getNodes().stream()
                .filter(node -> List.of("proposed", "replayed", "shadowed", "canary").contains(node.getRolloutStage()))
                .map(node -> node.getContentRevisionId())
                .distinct()
                .toList());
        assertEquals("rev-2", catalogEntry.getLatestCandidateRevisionId());
        assertEquals("rev-2", catalogEntry.getActiveRevisionId());
        assertEquals(List.of("run-2"), evidence.getRunIds());
        assertEquals(List.of("trace-2"), evidence.getTraceIds());
        assertEquals(List.of("span-2"), evidence.getSpanIds());
        assertFalse(lineage.getEdges().isEmpty());
    }

    @Test
    void shouldPreferMostRecentBundleBindingForActiveRevision() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(
                revision("rev-1", null, "planner v1", Instant.parse("2026-03-31T18:00:00Z")),
                revision("rev-2", "rev-1", "planner v2", Instant.parse("2026-03-31T19:00:00Z")),
                revision("rev-3", "rev-2", "planner v3", Instant.parse("2026-03-31T20:00:00Z"))));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactType("skill")
                .artifactSubtype("skill")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactAliases(List.of("skill:planner"))
                .contentRevisionId("rev-3")
                .baseContentRevisionId("rev-2")
                .status("approved_pending")
                .lifecycleState("approved")
                .rolloutStage("approved")
                .sourceRunIds(List.of("run-3"))
                .createdAt(Instant.parse("2026-03-31T20:00:00Z"))
                .build()));
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-old")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                        .createdAt(Instant.parse("2026-03-31T18:05:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-new")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-3"))
                        .createdAt(Instant.parse("2026-03-31T20:05:00Z"))
                        .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of());

        ArtifactCatalogEntry catalogEntry = service.listCatalog().getFirst();

        assertEquals("rev-3", catalogEntry.getActiveRevisionId());
    }

    @Test
    void shouldPreferLatestActiveBundleOverNewerCanaryBundleWhenResolvingActiveRevision() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(
                revision("rev-1", null, "planner v1", Instant.parse("2026-03-31T18:00:00Z")),
                revision("rev-2", "rev-1", "planner v2", Instant.parse("2026-03-31T19:00:00Z"))));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactType("skill")
                .artifactSubtype("skill")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactAliases(List.of("skill:planner"))
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .status("canary")
                .lifecycleState("candidate")
                .rolloutStage("canary")
                .sourceRunIds(List.of("run-2"))
                .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                .build()));
        when(artifactBundleService.getBundles()).thenReturn(List.of(
                ArtifactBundleRecord.builder()
                        .id("bundle-active")
                        .status("active")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                        .createdAt(Instant.parse("2026-03-31T18:05:00Z"))
                        .build(),
                ArtifactBundleRecord.builder()
                        .id("bundle-canary")
                        .status("canary")
                        .artifactRevisionBindings(Map.of("stream-1", "rev-2"))
                        .createdAt(Instant.parse("2026-03-31T19:05:00Z"))
                        .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of());

        ArtifactCatalogEntry catalogEntry = service.listCatalog().getFirst();

        assertEquals("rev-1", catalogEntry.getActiveRevisionId());
        assertEquals("rev-2", catalogEntry.getLatestCandidateRevisionId());
    }

    @Test
    void shouldReadEvidenceAnchorsFromPersistedRevisionWhenCandidateRecordIsMissing() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(ArtifactRevisionRecord.builder()
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .artifactSubtype("skill")
                .contentRevisionId("rev-1")
                .rawContent("planner v1")
                .sourceRunIds(List.of("run-1"))
                .traceIds(List.of("trace-persisted"))
                .spanIds(List.of("span-persisted"))
                .createdAt(Instant.parse("2026-03-31T18:00:00Z"))
                .build()));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of());
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of());
        when(artifactBundleService.getBundles()).thenReturn(List.of(ArtifactBundleRecord.builder()
                .id("bundle-active")
                .status("active")
                .artifactRevisionBindings(Map.of("stream-1", "rev-1"))
                .createdAt(Instant.parse("2026-03-31T18:05:00Z"))
                .build()));
        when(benchmarkLabService.getCampaigns()).thenReturn(List.of());

        ArtifactRevisionEvidenceProjection evidence = service.getRevisionEvidence("stream-1", "rev-1");

        assertEquals(List.of("trace-persisted"), evidence.getTraceIds());
        assertEquals(List.of("span-persisted"), evidence.getSpanIds());
    }

    @Test
    void shouldBuildRevisionAndTransitionCompareViewsFromLineage() {
        when(evolutionCandidateService.getArtifactRevisionRecords()).thenReturn(List.of(
                revision("rev-1", null, "planner v1", Instant.parse("2026-03-31T18:00:00Z")),
                revision("rev-2", "rev-1", "planner v2", Instant.parse("2026-03-31T19:00:00Z"))));
        when(promotionWorkflowService.getCandidates()).thenReturn(List.of(EvolutionCandidate.builder()
                .id("candidate-1")
                .artifactType("skill")
                .artifactSubtype("skill")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactAliases(List.of("skill:planner"))
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .status("shadowed")
                .lifecycleState("candidate")
                .rolloutStage("shadowed")
                .sourceRunIds(List.of("run-2"))
                .createdAt(Instant.parse("2026-03-31T19:00:00Z"))
                .build()));
        when(promotionWorkflowService.getPromotionDecisions()).thenReturn(List.of(decision(
                "decision-1",
                "shadowed",
                "candidate",
                "shadowed",
                Instant.parse("2026-03-31T19:10:00Z"))));
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
        ArtifactTransitionDiffProjection transitionDiff = service.getTransitionDiff(
                "stream-1",
                "candidate-1:proposed",
                "decision-1:shadowed");
        ArtifactCompareEvidenceProjection compareEvidence = service.getCompareEvidence("stream-1", "rev-1", "rev-2");

        assertEquals("rev-1", revisionDiff.getFromRevisionId());
        assertEquals("rev-2", revisionDiff.getToRevisionId());
        assertFalse(revisionDiff.getChangedFields().isEmpty());
        assertEquals("shadowed", transitionDiff.getToRolloutStage());
        assertTrue(transitionDiff.isContentChanged());
        assertEquals("rev-1", compareEvidence.getFromRevisionId());
        assertEquals("rev-2", compareEvidence.getToRevisionId());
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

    private PromotionDecision decision(
            String decisionId,
            String legacyState,
            String lifecycleState,
            String rolloutStage,
            Instant decidedAt) {
        return PromotionDecision.builder()
                .id(decisionId)
                .candidateId("candidate-1")
                .artifactType("skill")
                .artifactSubtype("skill")
                .artifactStreamId("stream-1")
                .originArtifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .contentRevisionId("rev-2")
                .baseContentRevisionId("rev-1")
                .state(legacyState)
                .fromState("proposed")
                .toState(legacyState)
                .fromLifecycleState("candidate")
                .toLifecycleState(lifecycleState)
                .fromRolloutStage("proposed")
                .toRolloutStage(rolloutStage)
                .originBundleId("bundle-1")
                .approvalRequestId("candidate-1-approval")
                .decidedAt(decidedAt)
                .build();
    }
}
