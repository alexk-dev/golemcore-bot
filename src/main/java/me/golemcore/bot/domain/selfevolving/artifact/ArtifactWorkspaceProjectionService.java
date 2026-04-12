package me.golemcore.bot.domain.selfevolving.artifact;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;

/**
 * Materializes artifact workspace projections from SelfEvolving source records.
 */
@Service
public class ArtifactWorkspaceProjectionService {

    private static final int PROJECTION_SCHEMA_VERSION = 1;
    private final ArtifactProjectionLookupService artifactProjectionLookupService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final BenchmarkLabService benchmarkLabService;
    private final ArtifactLineageProjectionService artifactLineageProjectionService;
    private final ArtifactEvidenceProjectionService artifactEvidenceProjectionService;
    private final ArtifactDiffProjectionService artifactDiffProjectionService;
    private final Clock clock;

    public ArtifactWorkspaceProjectionService(
            ArtifactProjectionLookupService artifactProjectionLookupService,
            PromotionWorkflowService promotionWorkflowService,
            BenchmarkLabService benchmarkLabService,
            ArtifactLineageProjectionService artifactLineageProjectionService,
            ArtifactEvidenceProjectionService artifactEvidenceProjectionService,
            ArtifactDiffProjectionService artifactDiffProjectionService,
            Clock clock) {
        this.artifactProjectionLookupService = artifactProjectionLookupService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.benchmarkLabService = benchmarkLabService;
        this.artifactLineageProjectionService = artifactLineageProjectionService;
        this.artifactEvidenceProjectionService = artifactEvidenceProjectionService;
        this.artifactDiffProjectionService = artifactDiffProjectionService;
        this.clock = clock;
    }

    public List<ArtifactCatalogEntry> listCatalog() {
        java.util.Map<String, List<ArtifactRevisionRecord>> revisionsByStream = artifactProjectionLookupService
                .revisionsByStream();
        List<ArtifactCatalogEntry> entries = new ArrayList<>();
        for (java.util.Map.Entry<String, List<ArtifactRevisionRecord>> entry : revisionsByStream.entrySet()) {
            List<ArtifactRevisionRecord> revisions = artifactProjectionLookupService.sortRevisions(entry.getValue());
            ArtifactRevisionRecord latestRevision = revisions.getLast();
            EvolutionCandidate latestCandidate = artifactProjectionLookupService.findLatestCandidate(entry.getKey())
                    .orElse(null);
            String activeRevisionId = artifactProjectionLookupService.resolveActiveRevisionId(entry.getKey())
                    .orElse(revisions.getFirst().getContentRevisionId());
            entries.add(ArtifactCatalogEntry.builder()
                    .artifactStreamId(entry.getKey())
                    .originArtifactStreamId(latestRevision.getOriginArtifactStreamId())
                    .artifactKey(latestRevision.getArtifactKey())
                    .artifactAliases(latestCandidate != null && latestCandidate.getArtifactAliases() != null
                            ? latestCandidate.getArtifactAliases()
                            : List.of(latestRevision.getArtifactKey()))
                    .artifactType(latestRevision.getArtifactType())
                    .artifactSubtype(latestRevision.getArtifactSubtype())
                    .displayName(latestRevision.getArtifactKey())
                    .latestRevisionId(latestRevision.getContentRevisionId())
                    .activeRevisionId(activeRevisionId)
                    .latestCandidateRevisionId(latestCandidate != null ? latestCandidate.getContentRevisionId() : null)
                    .currentLifecycleState(latestCandidate != null ? latestCandidate.getLifecycleState() : "active")
                    .currentRolloutStage(latestCandidate != null ? latestCandidate.getRolloutStage() : "active")
                    .hasRegression(Boolean.FALSE)
                    .hasPendingApproval(
                            latestCandidate != null && "approved_pending".equals(latestCandidate.getStatus()))
                    .campaignCount(artifactProjectionLookupService.resolveCampaignCount(entry.getKey()))
                    .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                    .updatedAt(latestRevision.getCreatedAt())
                    .projectedAt(Instant.now(clock))
                    .build());
        }
        entries.sort(Comparator.comparing(ArtifactCatalogEntry::getArtifactStreamId));
        return entries;
    }

    public ArtifactLineageProjection getLineage(String artifactStreamId) {
        EvolutionCandidate candidate = artifactProjectionLookupService.findLatestCandidate(artifactStreamId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact stream not found: " + artifactStreamId));
        List<PromotionDecision> decisions = promotionWorkflowService.getPromotionDecisions().stream()
                .filter(decision -> decision != null && artifactStreamId.equals(decision.getArtifactStreamId()))
                .toList();
        return artifactLineageProjectionService.project(
                artifactStreamId,
                candidate,
                decisions,
                benchmarkLabService.getCampaigns());
    }

    public List<ArtifactRevisionProjection> listRevisions(String artifactStreamId) {
        return artifactProjectionLookupService.sortRevisions(
                artifactProjectionLookupService.revisionsByStream().getOrDefault(artifactStreamId, List.of())).stream()
                .map(revision -> ArtifactRevisionProjection.builder()
                        .artifactStreamId(revision.getArtifactStreamId())
                        .originArtifactStreamId(revision.getOriginArtifactStreamId())
                        .artifactKey(revision.getArtifactKey())
                        .artifactType(revision.getArtifactType())
                        .artifactSubtype(revision.getArtifactSubtype())
                        .contentRevisionId(revision.getContentRevisionId())
                        .baseContentRevisionId(revision.getBaseContentRevisionId())
                        .rawContent(revision.getRawContent())
                        .sourceRunIds(artifactProjectionLookupService.resolveRunIdsForRevision(artifactStreamId,
                                revision))
                        .createdAt(revision.getCreatedAt())
                        .build())
                .toList();
    }

    public ArtifactRevisionDiffProjection getRevisionDiff(String artifactStreamId, String fromRevisionId,
            String toRevisionId) {
        return artifactDiffProjectionService.getRevisionDiff(artifactStreamId, fromRevisionId, toRevisionId);
    }

    public ArtifactTransitionDiffProjection getTransitionDiff(String artifactStreamId, String fromNodeId,
            String toNodeId) {
        return artifactDiffProjectionService.getTransitionDiff(
                artifactStreamId,
                getLineage(artifactStreamId),
                fromNodeId,
                toNodeId);
    }

    public ArtifactRevisionEvidenceProjection getRevisionEvidence(String artifactStreamId, String revisionId) {
        return artifactEvidenceProjectionService.getRevisionEvidence(artifactStreamId, revisionId);
    }

    public ArtifactCompareEvidenceProjection getCompareEvidence(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId) {
        return artifactEvidenceProjectionService.getCompareEvidence(artifactStreamId, fromRevisionId, toRevisionId);
    }

    public ArtifactTransitionEvidenceProjection getTransitionEvidence(
            String artifactStreamId,
            String fromNodeId,
            String toNodeId) {
        return artifactEvidenceProjectionService.getTransitionEvidence(
                artifactStreamId,
                getLineage(artifactStreamId),
                fromNodeId,
                toNodeId);
    }
}
