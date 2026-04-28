package me.golemcore.bot.adapter.inbound.web.projection;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCompareOptionsDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactRevisionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactTransitionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactWorkspaceSummaryDto;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCatalogEntry;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactCompareEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageEdge;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionEvidenceProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionEvidenceProjection;
import me.golemcore.bot.domain.selfevolving.artifact.ArtifactWorkspaceProjectionService;
import me.golemcore.bot.domain.support.StringValueSupport;
import org.springframework.stereotype.Service;

/**
 * Projects artifact workspace records into dashboard-friendly DTOs. Split out
 * of {@code SelfEvolvingProjectionService} so the artifact bounded context can
 * evolve independently of run/candidate/tactic projections.
 */
@Service
public class ArtifactProjectionService {

    private final ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService;

    public ArtifactProjectionService(ArtifactWorkspaceProjectionService artifactWorkspaceProjectionService) {
        this.artifactWorkspaceProjectionService = artifactWorkspaceProjectionService;
    }

    public List<SelfEvolvingArtifactCatalogEntryDto> listArtifacts(
            String artifactType,
            String artifactSubtype,
            String lifecycleState,
            String rolloutStage,
            Boolean hasPendingApproval,
            Boolean hasRegression,
            Boolean benchmarked,
            String query) {
        return artifactWorkspaceProjectionService.listCatalog().stream()
                .filter(entry -> matchesFilter(entry.getArtifactType(), artifactType))
                .filter(entry -> matchesFilter(entry.getArtifactSubtype(), artifactSubtype))
                .filter(entry -> matchesFilter(entry.getCurrentLifecycleState(), lifecycleState))
                .filter(entry -> matchesFilter(entry.getCurrentRolloutStage(), rolloutStage))
                .filter(entry -> hasPendingApproval == null
                        || hasPendingApproval.equals(Boolean.TRUE.equals(entry.getHasPendingApproval())))
                .filter(entry -> hasRegression == null
                        || hasRegression.equals(Boolean.TRUE.equals(entry.getHasRegression())))
                .filter(entry -> benchmarked == null
                        || benchmarked.equals(entry.getCampaignCount() != null && entry.getCampaignCount() > 0))
                .filter(entry -> matchesQuery(entry, query))
                .map(this::toArtifactCatalogDto)
                .toList();
    }

    public Optional<SelfEvolvingArtifactWorkspaceSummaryDto> getArtifactWorkspaceSummary(String artifactStreamId) {
        return artifactWorkspaceProjectionService.listCatalog().stream()
                .filter(entry -> entry != null && artifactStreamId.equals(entry.getArtifactStreamId()))
                .findFirst()
                .map(entry -> SelfEvolvingArtifactWorkspaceSummaryDto.builder()
                        .artifactStreamId(entry.getArtifactStreamId())
                        .originArtifactStreamId(entry.getOriginArtifactStreamId())
                        .artifactKey(entry.getArtifactKey())
                        .artifactAliases(entry.getArtifactAliases())
                        .artifactType(entry.getArtifactType())
                        .artifactSubtype(entry.getArtifactSubtype())
                        .activeRevisionId(entry.getActiveRevisionId())
                        .latestCandidateRevisionId(entry.getLatestCandidateRevisionId())
                        .currentLifecycleState(entry.getCurrentLifecycleState())
                        .currentRolloutStage(entry.getCurrentRolloutStage())
                        .campaignCount(entry.getCampaignCount())
                        .projectionSchemaVersion(entry.getProjectionSchemaVersion())
                        .updatedAt(formatInstant(entry.getUpdatedAt()))
                        .projectedAt(formatInstant(entry.getProjectedAt()))
                        .compareOptions(buildCompareOptions(artifactStreamId))
                        .build());
    }

    public Optional<SelfEvolvingArtifactLineageDto> getArtifactLineage(String artifactStreamId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(ignored -> toArtifactLineageDto(artifactWorkspaceProjectionService.getLineage(artifactStreamId)));
    }

    public Optional<SelfEvolvingArtifactRevisionDiffDto> getArtifactRevisionDiff(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(ignored -> toArtifactRevisionDiffDto(artifactWorkspaceProjectionService.getRevisionDiff(
                        artifactStreamId,
                        fromRevisionId,
                        toRevisionId)));
    }

    public Optional<SelfEvolvingArtifactTransitionDiffDto> getArtifactTransitionDiff(
            String artifactStreamId,
            String fromNodeId,
            String toNodeId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(ignored -> toArtifactTransitionDiffDto(artifactWorkspaceProjectionService.getTransitionDiff(
                        artifactStreamId,
                        fromNodeId,
                        toNodeId)));
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getArtifactRevisionEvidence(
            String artifactStreamId,
            String revisionId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(ignored -> toArtifactEvidenceDto(artifactWorkspaceProjectionService.getRevisionEvidence(
                        artifactStreamId,
                        revisionId)));
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getArtifactCompareEvidence(
            String artifactStreamId,
            String fromRevisionId,
            String toRevisionId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(ignored -> toArtifactCompareEvidenceDto(artifactWorkspaceProjectionService.getCompareEvidence(
                        artifactStreamId,
                        fromRevisionId,
                        toRevisionId)));
    }

    public Optional<SelfEvolvingArtifactEvidenceDto> getArtifactTransitionEvidence(
            String artifactStreamId,
            String fromNodeId,
            String toNodeId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(ignored -> toArtifactTransitionEvidenceDto(
                        artifactWorkspaceProjectionService.getTransitionEvidence(
                                artifactStreamId,
                                fromNodeId,
                                toNodeId)));
    }

    public Optional<SelfEvolvingArtifactCompareOptionsDto> getArtifactCompareOptions(String artifactStreamId) {
        return getArtifactWorkspaceSummary(artifactStreamId)
                .map(SelfEvolvingArtifactWorkspaceSummaryDto::getCompareOptions);
    }

    private SelfEvolvingArtifactCatalogEntryDto toArtifactCatalogDto(ArtifactCatalogEntry entry) {
        return SelfEvolvingArtifactCatalogEntryDto.builder()
                .artifactStreamId(entry.getArtifactStreamId())
                .originArtifactStreamId(entry.getOriginArtifactStreamId())
                .artifactKey(entry.getArtifactKey())
                .artifactAliases(entry.getArtifactAliases())
                .artifactType(entry.getArtifactType())
                .artifactSubtype(entry.getArtifactSubtype())
                .displayName(entry.getDisplayName())
                .latestRevisionId(entry.getLatestRevisionId())
                .activeRevisionId(entry.getActiveRevisionId())
                .latestCandidateRevisionId(entry.getLatestCandidateRevisionId())
                .currentLifecycleState(entry.getCurrentLifecycleState())
                .currentRolloutStage(entry.getCurrentRolloutStage())
                .hasRegression(entry.getHasRegression())
                .hasPendingApproval(entry.getHasPendingApproval())
                .campaignCount(entry.getCampaignCount())
                .projectionSchemaVersion(entry.getProjectionSchemaVersion())
                .updatedAt(formatInstant(entry.getUpdatedAt()))
                .projectedAt(formatInstant(entry.getProjectedAt()))
                .build();
    }

    private SelfEvolvingArtifactCompareOptionsDto buildCompareOptions(String artifactStreamId) {
        List<ArtifactRevisionProjection> revisions = artifactWorkspaceProjectionService.listRevisions(artifactStreamId);
        ArtifactLineageProjection lineageProjection = artifactWorkspaceProjectionService.getLineage(artifactStreamId);
        List<SelfEvolvingArtifactCompareOptionsDto.CompareOptionDto> revisionOptions = new ArrayList<>();
        String defaultFromRevisionId = "";
        String defaultToRevisionId = "";
        if (!revisions.isEmpty()) {
            ArtifactRevisionProjection latestRevision = revisions.getLast();
            ArtifactRevisionProjection previousRevision = revisions.size() > 1 ? revisions.get(revisions.size() - 2)
                    : null;
            ArtifactCatalogEntry catalogEntry = artifactWorkspaceProjectionService.listCatalog().stream()
                    .filter(entry -> artifactStreamId.equals(entry.getArtifactStreamId()))
                    .findFirst()
                    .orElse(null);
            defaultFromRevisionId = catalogEntry != null ? catalogEntry.getActiveRevisionId() : "";
            defaultToRevisionId = catalogEntry != null ? catalogEntry.getLatestCandidateRevisionId() : "";
            if (!StringValueSupport.isBlank(defaultFromRevisionId)
                    && !StringValueSupport.isBlank(defaultToRevisionId)) {
                revisionOptions.add(compareOption("active_vs_candidate", defaultFromRevisionId, defaultToRevisionId));
            }
            if (previousRevision != null) {
                revisionOptions.add(compareOption(
                        "previous_vs_latest",
                        previousRevision.getContentRevisionId(),
                        latestRevision.getContentRevisionId()));
            }
            if (StringValueSupport.isBlank(defaultFromRevisionId)) {
                defaultFromRevisionId = previousRevision != null ? previousRevision.getContentRevisionId()
                        : latestRevision.getContentRevisionId();
            }
            if (StringValueSupport.isBlank(defaultToRevisionId)) {
                defaultToRevisionId = latestRevision.getContentRevisionId();
            }
        }

        List<SelfEvolvingArtifactCompareOptionsDto.CompareOptionDto> transitionOptions = new ArrayList<>();
        List<String> railOrder = lineageProjection.getRailOrder() != null ? lineageProjection.getRailOrder()
                : List.of();
        String defaultFromNodeId = railOrder.size() > 1 ? railOrder.get(railOrder.size() - 2)
                : !railOrder.isEmpty() ? railOrder.getFirst() : null;
        String defaultToNodeId = !railOrder.isEmpty() ? railOrder.getLast() : null;
        for (int index = 1; index < railOrder.size(); index++) {
            transitionOptions.add(compareOption(
                    "transition_" + index,
                    railOrder.get(index - 1),
                    railOrder.get(index)));
        }
        return SelfEvolvingArtifactCompareOptionsDto.builder()
                .artifactStreamId(artifactStreamId)
                .defaultFromRevisionId(StringValueSupport.isBlank(defaultFromRevisionId) ? null : defaultFromRevisionId)
                .defaultToRevisionId(StringValueSupport.isBlank(defaultToRevisionId) ? null : defaultToRevisionId)
                .defaultFromNodeId(defaultFromNodeId)
                .defaultToNodeId(defaultToNodeId)
                .revisionOptions(revisionOptions)
                .transitionOptions(transitionOptions)
                .build();
    }

    private SelfEvolvingArtifactCompareOptionsDto.CompareOptionDto compareOption(String label, String fromId,
            String toId) {
        return SelfEvolvingArtifactCompareOptionsDto.CompareOptionDto.builder()
                .label(label)
                .fromId(fromId)
                .toId(toId)
                .build();
    }

    private SelfEvolvingArtifactLineageDto toArtifactLineageDto(ArtifactLineageProjection projection) {
        return SelfEvolvingArtifactLineageDto.builder()
                .artifactStreamId(projection.getArtifactStreamId())
                .originArtifactStreamId(projection.getOriginArtifactStreamId())
                .artifactKey(projection.getArtifactKey())
                .nodes(projection.getNodes().stream().map(this::toArtifactLineageNodeDto).toList())
                .edges(projection.getEdges().stream().map(this::toArtifactLineageEdgeDto).toList())
                .railOrder(projection.getRailOrder())
                .branches(projection.getBranches())
                .defaultSelectedNodeId(projection.getDefaultSelectedNodeId())
                .defaultSelectedRevisionId(projection.getDefaultSelectedRevisionId())
                .projectionSchemaVersion(projection.getProjectionSchemaVersion())
                .projectedAt(formatInstant(projection.getProjectedAt()))
                .build();
    }

    private SelfEvolvingArtifactLineageDto.NodeDto toArtifactLineageNodeDto(ArtifactLineageNode node) {
        return SelfEvolvingArtifactLineageDto.NodeDto.builder()
                .nodeId(node.getNodeId())
                .contentRevisionId(node.getContentRevisionId())
                .lifecycleState(node.getLifecycleState())
                .rolloutStage(node.getRolloutStage())
                .promotionDecisionId(node.getPromotionDecisionId())
                .originBundleId(node.getOriginBundleId())
                .sourceRunIds(node.getSourceRunIds())
                .campaignIds(node.getCampaignIds())
                .attributionMode(node.getAttributionMode())
                .createdAt(formatInstant(node.getCreatedAt()))
                .build();
    }

    private SelfEvolvingArtifactLineageDto.EdgeDto toArtifactLineageEdgeDto(ArtifactLineageEdge edge) {
        return SelfEvolvingArtifactLineageDto.EdgeDto.builder()
                .edgeId(edge.getEdgeId())
                .fromNodeId(edge.getFromNodeId())
                .toNodeId(edge.getToNodeId())
                .edgeType(edge.getEdgeType())
                .createdAt(formatInstant(edge.getCreatedAt()))
                .build();
    }

    private SelfEvolvingArtifactRevisionDiffDto toArtifactRevisionDiffDto(ArtifactRevisionDiffProjection projection) {
        return SelfEvolvingArtifactRevisionDiffDto.builder()
                .artifactStreamId(projection.getArtifactStreamId())
                .artifactKey(projection.getArtifactKey())
                .fromRevisionId(projection.getFromRevisionId())
                .toRevisionId(projection.getToRevisionId())
                .summary(projection.getSummary())
                .semanticSections(projection.getSemanticSections())
                .rawPatch(projection.getRawPatch())
                .changedFields(projection.getChangedFields())
                .riskSignals(projection.getRiskSignals())
                .impactSummary(projection.getImpactSummary())
                .attributionMode(projection.getAttributionMode())
                .projectionSchemaVersion(projection.getProjectionSchemaVersion())
                .projectedAt(formatInstant(projection.getProjectedAt()))
                .build();
    }

    private SelfEvolvingArtifactTransitionDiffDto toArtifactTransitionDiffDto(
            ArtifactTransitionDiffProjection projection) {
        return SelfEvolvingArtifactTransitionDiffDto.builder()
                .artifactStreamId(projection.getArtifactStreamId())
                .artifactKey(projection.getArtifactKey())
                .fromNodeId(projection.getFromNodeId())
                .toNodeId(projection.getToNodeId())
                .fromRevisionId(projection.getFromRevisionId())
                .toRevisionId(projection.getToRevisionId())
                .fromRolloutStage(projection.getFromRolloutStage())
                .toRolloutStage(projection.getToRolloutStage())
                .contentChanged(projection.isContentChanged())
                .summary(projection.getSummary())
                .impactSummary(projection.getImpactSummary())
                .attributionMode(projection.getAttributionMode())
                .projectionSchemaVersion(projection.getProjectionSchemaVersion())
                .projectedAt(formatInstant(projection.getProjectedAt()))
                .build();
    }

    private SelfEvolvingArtifactEvidenceDto toArtifactEvidenceDto(ArtifactRevisionEvidenceProjection projection) {
        return SelfEvolvingArtifactEvidenceDto.builder()
                .artifactStreamId(projection.getArtifactStreamId())
                .artifactKey(projection.getArtifactKey())
                .payloadKind("revision")
                .revisionId(projection.getRevisionId())
                .runIds(projection.getRunIds())
                .traceIds(projection.getTraceIds())
                .spanIds(projection.getSpanIds())
                .campaignIds(projection.getCampaignIds())
                .promotionDecisionIds(projection.getPromotionDecisionIds())
                .approvalRequestIds(projection.getApprovalRequestIds())
                .findings(projection.getFindings())
                .projectionSchemaVersion(projection.getProjectionSchemaVersion())
                .projectedAt(formatInstant(projection.getProjectedAt()))
                .build();
    }

    private SelfEvolvingArtifactEvidenceDto toArtifactCompareEvidenceDto(ArtifactCompareEvidenceProjection projection) {
        return SelfEvolvingArtifactEvidenceDto.builder()
                .artifactStreamId(projection.getArtifactStreamId())
                .artifactKey(projection.getArtifactKey())
                .payloadKind("compare")
                .fromRevisionId(projection.getFromRevisionId())
                .toRevisionId(projection.getToRevisionId())
                .runIds(projection.getRunIds())
                .traceIds(projection.getTraceIds())
                .spanIds(projection.getSpanIds())
                .campaignIds(projection.getCampaignIds())
                .promotionDecisionIds(projection.getPromotionDecisionIds())
                .approvalRequestIds(projection.getApprovalRequestIds())
                .findings(projection.getFindings())
                .projectionSchemaVersion(projection.getProjectionSchemaVersion())
                .projectedAt(formatInstant(projection.getProjectedAt()))
                .build();
    }

    private SelfEvolvingArtifactEvidenceDto toArtifactTransitionEvidenceDto(
            ArtifactTransitionEvidenceProjection projection) {
        return SelfEvolvingArtifactEvidenceDto.builder()
                .artifactStreamId(projection.getArtifactStreamId())
                .artifactKey(projection.getArtifactKey())
                .payloadKind("transition")
                .fromNodeId(projection.getFromNodeId())
                .toNodeId(projection.getToNodeId())
                .fromRevisionId(projection.getFromRevisionId())
                .toRevisionId(projection.getToRevisionId())
                .runIds(projection.getRunIds())
                .traceIds(projection.getTraceIds())
                .spanIds(projection.getSpanIds())
                .campaignIds(projection.getCampaignIds())
                .promotionDecisionIds(projection.getPromotionDecisionIds())
                .approvalRequestIds(projection.getApprovalRequestIds())
                .findings(projection.getFindings())
                .projectionSchemaVersion(projection.getProjectionSchemaVersion())
                .projectedAt(formatInstant(projection.getProjectedAt()))
                .build();
    }

    private boolean matchesFilter(String actualValue, String expectedValue) {
        return StringValueSupport.isBlank(expectedValue)
                || StringValueSupport.nullSafe(actualValue).equalsIgnoreCase(expectedValue);
    }

    private boolean matchesQuery(ArtifactCatalogEntry entry, String query) {
        if (StringValueSupport.isBlank(query)) {
            return true;
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        if (StringValueSupport.nullSafe(entry.getArtifactStreamId()).toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || StringValueSupport.nullSafe(entry.getArtifactKey()).toLowerCase(Locale.ROOT)
                        .contains(normalizedQuery)
                || StringValueSupport.nullSafe(entry.getDisplayName()).toLowerCase(Locale.ROOT)
                        .contains(normalizedQuery)) {
            return true;
        }
        return entry.getArtifactAliases() != null && entry.getArtifactAliases().stream()
                .filter(alias -> !StringValueSupport.isBlank(alias))
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .anyMatch(alias -> alias.contains(normalizedQuery));
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
