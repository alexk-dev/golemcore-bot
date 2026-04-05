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
import me.golemcore.bot.domain.model.selfevolving.VerdictEvidenceRef;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds evidence projections for revisions, compares, and rollout transitions.
 */
@Service
public class ArtifactEvidenceProjectionService {

    private static final int PROJECTION_SCHEMA_VERSION = 1;

    private final EvolutionCandidateService evolutionCandidateService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final ArtifactBundleService artifactBundleService;
    private final BenchmarkLabService benchmarkLabService;
    private final Clock clock;

    public ArtifactEvidenceProjectionService(
            EvolutionCandidateService evolutionCandidateService,
            PromotionWorkflowService promotionWorkflowService,
            ArtifactBundleService artifactBundleService,
            BenchmarkLabService benchmarkLabService,
            Clock clock) {
        this.evolutionCandidateService = evolutionCandidateService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.artifactBundleService = artifactBundleService;
        this.benchmarkLabService = benchmarkLabService;
        this.clock = clock;
    }

    public ArtifactRevisionEvidenceProjection getRevisionEvidence(String artifactStreamId, String revisionId) {
        ArtifactRevisionRecord revision = findRevision(artifactStreamId, revisionId)
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + revisionId));
        List<String> campaignIds = resolveCampaignIdsForRevision(artifactStreamId, revisionId);
        List<String> promotionDecisionIds = promotionWorkflowService.getPromotionDecisions().stream()
                .filter(decision -> decision != null && artifactStreamId.equals(decision.getArtifactStreamId()))
                .filter(decision -> revisionId.equals(decision.getContentRevisionId()))
                .map(PromotionDecision::getId)
                .toList();
        List<String> approvalRequestIds = promotionWorkflowService.getPromotionDecisions().stream()
                .filter(decision -> decision != null && artifactStreamId.equals(decision.getArtifactStreamId()))
                .filter(decision -> revisionId.equals(decision.getContentRevisionId()))
                .map(PromotionDecision::getApprovalRequestId)
                .filter(value -> !StringValueSupport.isBlank(value))
                .toList();
        Set<String> traceIds = new LinkedHashSet<>();
        Set<String> spanIds = new LinkedHashSet<>();
        collectRevisionEvidenceAnchorIds(revision, traceIds, spanIds);
        findCandidateByRevision(artifactStreamId, revisionId)
                .ifPresent(candidate -> collectEvidenceAnchorIds(candidate, traceIds, spanIds));
        return ArtifactRevisionEvidenceProjection.builder()
                .artifactStreamId(artifactStreamId)
                .artifactKey(revision.getArtifactKey())
                .revisionId(revisionId)
                .runIds(resolveRunIdsForRevision(artifactStreamId, revision))
                .traceIds(new ArrayList<>(traceIds))
                .spanIds(new ArrayList<>(spanIds))
                .campaignIds(campaignIds)
                .promotionDecisionIds(promotionDecisionIds)
                .approvalRequestIds(approvalRequestIds)
                .findings(List.of("revision_evidence"))
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now(clock))
                .build();
    }

    public ArtifactCompareEvidenceProjection getCompareEvidence(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId) {
        ArtifactRevisionEvidenceProjection fromEvidence = getRevisionEvidence(artifactStreamId, fromRevisionId);
        ArtifactRevisionEvidenceProjection toEvidence = getRevisionEvidence(artifactStreamId, toRevisionId);
        Set<String> runIds = new LinkedHashSet<>(fromEvidence.getRunIds());
        runIds.addAll(toEvidence.getRunIds());
        Set<String> campaignIds = new LinkedHashSet<>(fromEvidence.getCampaignIds());
        campaignIds.addAll(toEvidence.getCampaignIds());
        Set<String> promotionDecisionIds = new LinkedHashSet<>(fromEvidence.getPromotionDecisionIds());
        promotionDecisionIds.addAll(toEvidence.getPromotionDecisionIds());
        Set<String> approvalRequestIds = new LinkedHashSet<>(fromEvidence.getApprovalRequestIds());
        approvalRequestIds.addAll(toEvidence.getApprovalRequestIds());
        Set<String> traceIds = new LinkedHashSet<>(fromEvidence.getTraceIds());
        traceIds.addAll(toEvidence.getTraceIds());
        Set<String> spanIds = new LinkedHashSet<>(fromEvidence.getSpanIds());
        spanIds.addAll(toEvidence.getSpanIds());
        return ArtifactCompareEvidenceProjection.builder()
                .artifactStreamId(artifactStreamId)
                .artifactKey(fromEvidence.getArtifactKey())
                .fromRevisionId(fromRevisionId)
                .toRevisionId(toRevisionId)
                .runIds(new ArrayList<>(runIds))
                .traceIds(new ArrayList<>(traceIds))
                .spanIds(new ArrayList<>(spanIds))
                .campaignIds(new ArrayList<>(campaignIds))
                .promotionDecisionIds(new ArrayList<>(promotionDecisionIds))
                .approvalRequestIds(new ArrayList<>(approvalRequestIds))
                .findings(List.of("compare_evidence"))
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now(clock))
                .build();
    }

    public ArtifactTransitionEvidenceProjection getTransitionEvidence(
            String artifactStreamId,
            ArtifactLineageProjection lineage,
            String fromNodeId,
            String toNodeId) {
        ArtifactLineageNode fromNode = lineage.getNodes().stream()
                .filter(node -> node != null && fromNodeId.equals(node.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + fromNodeId));
        ArtifactLineageNode toNode = lineage.getNodes().stream()
                .filter(node -> node != null && toNodeId.equals(node.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + toNodeId));
        Set<String> runIds = new LinkedHashSet<>();
        Set<String> traceIds = new LinkedHashSet<>();
        Set<String> spanIds = new LinkedHashSet<>();
        Set<String> campaignIds = new LinkedHashSet<>();
        Set<String> promotionDecisionIds = new LinkedHashSet<>();
        Set<String> approvalRequestIds = new LinkedHashSet<>();
        collectTransitionNodeEvidence(
                artifactStreamId,
                fromNode,
                runIds,
                traceIds,
                spanIds,
                campaignIds,
                promotionDecisionIds,
                approvalRequestIds);
        collectTransitionNodeEvidence(
                artifactStreamId,
                toNode,
                runIds,
                traceIds,
                spanIds,
                campaignIds,
                promotionDecisionIds,
                approvalRequestIds);
        return ArtifactTransitionEvidenceProjection.builder()
                .artifactStreamId(artifactStreamId)
                .artifactKey(findRevision(artifactStreamId, toNode.getContentRevisionId())
                        .map(ArtifactRevisionRecord::getArtifactKey)
                        .orElse(null))
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .fromRevisionId(fromNode.getContentRevisionId())
                .toRevisionId(toNode.getContentRevisionId())
                .runIds(new ArrayList<>(runIds))
                .traceIds(new ArrayList<>(traceIds))
                .spanIds(new ArrayList<>(spanIds))
                .campaignIds(new ArrayList<>(campaignIds))
                .promotionDecisionIds(new ArrayList<>(promotionDecisionIds))
                .approvalRequestIds(new ArrayList<>(approvalRequestIds))
                .findings(List.of("transition_evidence"))
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now(clock))
                .build();
    }

    private Optional<ArtifactRevisionRecord> findRevision(String artifactStreamId, String revisionId) {
        return evolutionCandidateService.getArtifactRevisionRecords().stream()
                .filter(revision -> revision != null && artifactStreamId.equals(revision.getArtifactStreamId()))
                .filter(revision -> revisionId.equals(revision.getContentRevisionId()))
                .findFirst();
    }

    private Optional<EvolutionCandidate> findCandidateByRevision(String artifactStreamId, String revisionId) {
        return promotionWorkflowService.getCandidates().stream()
                .filter(candidate -> candidate != null && artifactStreamId.equals(candidate.getArtifactStreamId()))
                .filter(candidate -> revisionId.equals(candidate.getContentRevisionId()))
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

    private List<String> resolveCampaignIds(String bundleId) {
        if (StringValueSupport.isBlank(bundleId)) {
            return List.of();
        }
        return benchmarkLabService.getCampaigns().stream()
                .filter(campaign -> campaign != null)
                .filter(campaign -> bundleId.equals(campaign.getBaselineBundleId())
                        || bundleId.equals(campaign.getCandidateBundleId()))
                .map(BenchmarkCampaign::getId)
                .toList();
    }

    private List<String> resolveCampaignIdsForRevision(String artifactStreamId, String revisionId) {
        List<String> campaignIds = new ArrayList<>();
        for (ArtifactBundleRecord bundle : artifactBundleService.getBundles()) {
            if (bundle == null || bundle.getArtifactRevisionBindings() == null) {
                continue;
            }
            if (revisionId.equals(bundle.getArtifactRevisionBindings().get(artifactStreamId))) {
                campaignIds.addAll(resolveCampaignIds(bundle.getId()));
            }
        }
        return campaignIds.stream().distinct().toList();
    }

    private void collectTransitionNodeEvidence(
            String artifactStreamId,
            ArtifactLineageNode node,
            Set<String> runIds,
            Set<String> traceIds,
            Set<String> spanIds,
            Set<String> campaignIds,
            Set<String> promotionDecisionIds,
            Set<String> approvalRequestIds) {
        if (node == null) {
            return;
        }
        if (node.getSourceRunIds() != null) {
            runIds.addAll(node.getSourceRunIds());
        }
        if (node.getCampaignIds() != null) {
            campaignIds.addAll(node.getCampaignIds());
        }
        if (!StringValueSupport.isBlank(node.getContentRevisionId())) {
            findRevision(artifactStreamId, node.getContentRevisionId())
                    .ifPresent(revision -> collectRevisionEvidenceAnchorIds(revision, traceIds, spanIds));
            findCandidateByRevision(artifactStreamId, node.getContentRevisionId())
                    .ifPresent(candidate -> collectEvidenceAnchorIds(candidate, traceIds, spanIds));
        }
        if (!StringValueSupport.isBlank(node.getPromotionDecisionId())) {
            promotionDecisionIds.add(node.getPromotionDecisionId());
            findDecisionById(node.getPromotionDecisionId())
                    .map(PromotionDecision::getApprovalRequestId)
                    .filter(value -> !StringValueSupport.isBlank(value))
                    .ifPresent(approvalRequestIds::add);
        }
    }

    private void collectEvidenceAnchorIds(
            EvolutionCandidate candidate,
            Set<String> traceIds,
            Set<String> spanIds) {
        if (candidate == null || candidate.getEvidenceRefs() == null) {
            return;
        }
        for (VerdictEvidenceRef evidenceRef : candidate.getEvidenceRefs()) {
            if (evidenceRef == null) {
                continue;
            }
            if (!StringValueSupport.isBlank(evidenceRef.getTraceId())) {
                traceIds.add(evidenceRef.getTraceId());
            }
            if (!StringValueSupport.isBlank(evidenceRef.getSpanId())) {
                spanIds.add(evidenceRef.getSpanId());
            }
        }
    }

    private void collectRevisionEvidenceAnchorIds(
            ArtifactRevisionRecord revision,
            Set<String> traceIds,
            Set<String> spanIds) {
        if (revision == null) {
            return;
        }
        if (revision.getTraceIds() != null) {
            revision.getTraceIds().stream()
                    .filter(traceId -> !StringValueSupport.isBlank(traceId))
                    .forEach(traceIds::add);
        }
        if (revision.getSpanIds() != null) {
            revision.getSpanIds().stream()
                    .filter(spanId -> !StringValueSupport.isBlank(spanId))
                    .forEach(spanIds::add);
        }
    }
}
