package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.domain.model.selfevolving.ArtifactBundleRecord;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes artifact workspace projections from SelfEvolving source records.
 */
@Service
public class ArtifactWorkspaceProjectionService {

    private static final int PROJECTION_SCHEMA_VERSION = 1;
    private static final Comparator<ArtifactBundleRecord> BUNDLE_RECENCY_COMPARATOR = Comparator
            .comparing(ArtifactBundleRecord::getActivatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArtifactBundleRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArtifactBundleRecord::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final EvolutionCandidateService evolutionCandidateService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final ArtifactBundleService artifactBundleService;
    private final BenchmarkLabService benchmarkLabService;
    private final ArtifactLineageProjectionService artifactLineageProjectionService;
    private final ArtifactEvidenceProjectionService artifactEvidenceProjectionService;
    private final ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService;
    private final ArtifactDiffService artifactDiffService;
    private final ArtifactImpactService artifactImpactService;
    private final Clock clock;

    public ArtifactWorkspaceProjectionService(
            EvolutionCandidateService evolutionCandidateService,
            PromotionWorkflowService promotionWorkflowService,
            ArtifactBundleService artifactBundleService,
            BenchmarkLabService benchmarkLabService,
            ArtifactLineageProjectionService artifactLineageProjectionService,
            ArtifactEvidenceProjectionService artifactEvidenceProjectionService,
            ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService,
            ArtifactDiffService artifactDiffService,
            ArtifactImpactService artifactImpactService,
            Clock clock) {
        this.evolutionCandidateService = evolutionCandidateService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.artifactBundleService = artifactBundleService;
        this.benchmarkLabService = benchmarkLabService;
        this.artifactLineageProjectionService = artifactLineageProjectionService;
        this.artifactEvidenceProjectionService = artifactEvidenceProjectionService;
        this.normalizedRevisionProjectionService = normalizedRevisionProjectionService;
        this.artifactDiffService = artifactDiffService;
        this.artifactImpactService = artifactImpactService;
        this.clock = clock;
    }

    public List<ArtifactCatalogEntry> listCatalog() {
        Map<String, List<ArtifactRevisionRecord>> revisionsByStream = revisionsByStream();
        List<ArtifactCatalogEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<ArtifactRevisionRecord>> entry : revisionsByStream.entrySet()) {
            List<ArtifactRevisionRecord> revisions = sortRevisions(entry.getValue());
            ArtifactRevisionRecord latestRevision = revisions.getLast();
            EvolutionCandidate latestCandidate = findLatestCandidate(entry.getKey()).orElse(null);
            String activeRevisionId = resolveActiveRevisionId(entry.getKey())
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
                    .campaignCount(resolveCampaignCount(entry.getKey()))
                    .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                    .updatedAt(latestRevision.getCreatedAt())
                    .projectedAt(Instant.now(clock))
                    .build());
        }
        entries.sort(Comparator.comparing(ArtifactCatalogEntry::getArtifactStreamId));
        return entries;
    }

    public ArtifactLineageProjection getLineage(String artifactStreamId) {
        EvolutionCandidate candidate = findLatestCandidate(artifactStreamId)
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
        return sortRevisions(revisionsByStream().getOrDefault(artifactStreamId, List.of())).stream()
                .map(revision -> ArtifactRevisionProjection.builder()
                        .artifactStreamId(revision.getArtifactStreamId())
                        .originArtifactStreamId(revision.getOriginArtifactStreamId())
                        .artifactKey(revision.getArtifactKey())
                        .artifactType(revision.getArtifactType())
                        .artifactSubtype(revision.getArtifactSubtype())
                        .contentRevisionId(revision.getContentRevisionId())
                        .baseContentRevisionId(revision.getBaseContentRevisionId())
                        .rawContent(revision.getRawContent())
                        .sourceRunIds(resolveRunIdsForRevision(artifactStreamId, revision))
                        .createdAt(revision.getCreatedAt())
                        .build())
                .toList();
    }

    public ArtifactRevisionDiffProjection getRevisionDiff(String artifactStreamId, String fromRevisionId,
            String toRevisionId) {
        ArtifactRevisionRecord fromRevision = findRevision(artifactStreamId, fromRevisionId)
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + fromRevisionId));
        ArtifactRevisionRecord toRevision = findRevision(artifactStreamId, toRevisionId)
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + toRevisionId));
        ArtifactImpactProjection impactProjection = artifactImpactService.summarizeImpact(
                artifactStreamId,
                fromRevisionId,
                toRevisionId,
                findBundleByRevision(artifactStreamId, fromRevisionId).orElse(null),
                findBundleByRevision(artifactStreamId, toRevisionId).orElse(null),
                benchmarkLabService.getCampaigns(),
                Set.of());
        ArtifactNormalizedRevisionProjection fromProjection = normalizedRevisionProjectionService
                .normalize(fromRevision);
        ArtifactNormalizedRevisionProjection toProjection = normalizedRevisionProjectionService.normalize(toRevision);
        return artifactDiffService.compareRevisions(
                artifactStreamId,
                fromRevision.getArtifactKey(),
                fromProjection,
                toProjection,
                impactProjection);
    }

    public ArtifactTransitionDiffProjection getTransitionDiff(String artifactStreamId, String fromNodeId,
            String toNodeId) {
        ArtifactLineageProjection lineage = getLineage(artifactStreamId);
        ArtifactLineageNode fromNode = lineage.getNodes().stream()
                .filter(node -> node != null && fromNodeId.equals(node.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + fromNodeId));
        ArtifactLineageNode toNode = lineage.getNodes().stream()
                .filter(node -> node != null && toNodeId.equals(node.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + toNodeId));
        ArtifactRevisionDiffProjection revisionDiff = null;
        if (!StringValueSupport.nullSafe(fromNode.getContentRevisionId())
                .equals(StringValueSupport.nullSafe(toNode.getContentRevisionId()))) {
            revisionDiff = getRevisionDiff(artifactStreamId, fromNode.getContentRevisionId(),
                    toNode.getContentRevisionId());
        } else {
            String transitionBaseRevisionId = resolveTransitionBaseRevisionId(toNode);
            if (!StringValueSupport.isBlank(transitionBaseRevisionId)
                    && !StringValueSupport.nullSafe(transitionBaseRevisionId)
                            .equals(StringValueSupport.nullSafe(toNode.getContentRevisionId()))) {
                revisionDiff = getRevisionDiff(artifactStreamId, transitionBaseRevisionId,
                        toNode.getContentRevisionId());
            }
        }
        ArtifactImpactProjection impactProjection = artifactImpactService.summarizeImpact(
                artifactStreamId,
                fromNode.getContentRevisionId(),
                toNode.getContentRevisionId(),
                findBundleByRevision(artifactStreamId, fromNode.getContentRevisionId()).orElse(null),
                findBundleByRevision(artifactStreamId, toNode.getContentRevisionId()).orElse(null),
                benchmarkLabService.getCampaigns(),
                Set.of());
        return artifactDiffService.compareTransition(
                artifactStreamId,
                findRevision(artifactStreamId, toNode.getContentRevisionId())
                        .map(ArtifactRevisionRecord::getArtifactKey)
                        .orElse(null),
                fromNode,
                toNode,
                revisionDiff,
                impactProjection);
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

    private Map<String, List<ArtifactRevisionRecord>> revisionsByStream() {
        Map<String, List<ArtifactRevisionRecord>> revisionsByStream = new LinkedHashMap<>();
        for (ArtifactRevisionRecord revision : evolutionCandidateService.getArtifactRevisionRecords()) {
            if (revision == null || StringValueSupport.isBlank(revision.getArtifactStreamId())) {
                continue;
            }
            revisionsByStream.computeIfAbsent(revision.getArtifactStreamId(), ignored -> new ArrayList<>())
                    .add(revision);
        }
        return revisionsByStream;
    }

    private List<ArtifactRevisionRecord> sortRevisions(List<ArtifactRevisionRecord> revisions) {
        List<ArtifactRevisionRecord> sorted = new ArrayList<>(revisions);
        sorted.sort(Comparator.comparing(ArtifactRevisionRecord::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    private Optional<EvolutionCandidate> findLatestCandidate(String artifactStreamId) {
        return promotionWorkflowService.getCandidates().stream()
                .filter(candidate -> candidate != null && artifactStreamId.equals(candidate.getArtifactStreamId()))
                .max(Comparator.comparing(EvolutionCandidate::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private Optional<EvolutionCandidate> findCandidateById(String candidateId) {
        return promotionWorkflowService.getCandidates().stream()
                .filter(candidate -> candidate != null && candidateId.equals(candidate.getId()))
                .findFirst();
    }

    private Optional<PromotionDecision> findDecisionById(String decisionId) {
        return promotionWorkflowService.getPromotionDecisions().stream()
                .filter(decision -> decision != null && decisionId.equals(decision.getId()))
                .findFirst();
    }

    private List<String> resolveRunIdsForRevision(String artifactStreamId, ArtifactRevisionRecord revision) {
        Set<String> runIds = new LinkedHashSet<>();
        if (revision.getSourceRunIds() != null) {
            runIds.addAll(revision.getSourceRunIds());
        }
        Set<String> candidateRunIds = new LinkedHashSet<>();
        for (EvolutionCandidate candidate : promotionWorkflowService.getCandidates()) {
            if (candidate == null || !artifactStreamId.equals(candidate.getArtifactStreamId())) {
                continue;
            }
            if (!revision.getContentRevisionId().equals(candidate.getContentRevisionId())) {
                continue;
            }
            if (candidate.getSourceRunIds() != null) {
                candidateRunIds.addAll(candidate.getSourceRunIds());
            }
        }
        if (!candidateRunIds.isEmpty()) {
            return new ArrayList<>(candidateRunIds);
        }
        return new ArrayList<>(runIds);
    }

    private Optional<String> resolveActiveRevisionId(String artifactStreamId) {
        List<ArtifactBundleRecord> bundles = artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && bundle.getArtifactRevisionBindings() != null)
                .filter(bundle -> !StringValueSupport
                        .isBlank(bundle.getArtifactRevisionBindings().get(artifactStreamId)))
                .toList();
        return bundles.stream()
                .filter(bundle -> "active".equalsIgnoreCase(bundle.getStatus()))
                .max(BUNDLE_RECENCY_COMPARATOR)
                .or(() -> bundles.stream()
                        .filter(bundle -> StringValueSupport.isBlank(bundle.getStatus())
                                || "snapshot".equalsIgnoreCase(bundle.getStatus()))
                        .max(BUNDLE_RECENCY_COMPARATOR))
                .or(() -> bundles.stream().max(BUNDLE_RECENCY_COMPARATOR))
                .map(bundle -> bundle.getArtifactRevisionBindings().get(artifactStreamId));
    }

    private int resolveCampaignCount(String artifactStreamId) {
        int campaignCount = 0;
        for (BenchmarkCampaign campaign : benchmarkLabService.getCampaigns()) {
            if (campaign == null) {
                continue;
            }
            if (findBundleById(campaign.getBaselineBundleId())
                    .filter(bundle -> bundle.getArtifactRevisionBindings() != null
                            && bundle.getArtifactRevisionBindings().containsKey(artifactStreamId))
                    .isPresent()
                    || findBundleById(campaign.getCandidateBundleId())
                            .filter(bundle -> bundle.getArtifactRevisionBindings() != null
                                    && bundle.getArtifactRevisionBindings().containsKey(artifactStreamId))
                            .isPresent()) {
                campaignCount++;
            }
        }
        return campaignCount;
    }

    private Optional<ArtifactRevisionRecord> findRevision(String artifactStreamId, String revisionId) {
        return evolutionCandidateService.getArtifactRevisionRecords().stream()
                .filter(revision -> revision != null && artifactStreamId.equals(revision.getArtifactStreamId()))
                .filter(revision -> revisionId.equals(revision.getContentRevisionId()))
                .findFirst();
    }

    private Optional<ArtifactBundleRecord> findBundleByRevision(String artifactStreamId, String revisionId) {
        return artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && bundle.getArtifactRevisionBindings() != null)
                .filter(bundle -> revisionId.equals(bundle.getArtifactRevisionBindings().get(artifactStreamId)))
                .max(BUNDLE_RECENCY_COMPARATOR);
    }

    private Optional<ArtifactBundleRecord> findBundleById(String bundleId) {
        return artifactBundleService.getBundles().stream()
                .filter(bundle -> bundle != null && bundleId.equals(bundle.getId()))
                .findFirst();
    }

    private String resolveTransitionBaseRevisionId(ArtifactLineageNode node) {
        if (node == null) {
            return null;
        }
        if (!StringValueSupport.isBlank(node.getPromotionDecisionId())) {
            Optional<PromotionDecision> decision = findDecisionById(node.getPromotionDecisionId());
            if (decision.isPresent() && !StringValueSupport.isBlank(decision.get().getBaseContentRevisionId())) {
                return decision.get().getBaseContentRevisionId();
            }
        }
        int separatorIndex = node.getNodeId() != null ? node.getNodeId().indexOf(':') : -1;
        if (separatorIndex > 0) {
            String candidateId = node.getNodeId().substring(0, separatorIndex);
            Optional<EvolutionCandidate> candidate = findCandidateById(candidateId);
            if (candidate.isPresent() && !StringValueSupport.isBlank(candidate.get().getBaseContentRevisionId())) {
                return candidate.get().getBaseContentRevisionId();
            }
        }
        return null;
    }

}
