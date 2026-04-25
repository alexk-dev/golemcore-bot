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

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.EvolutionCandidate;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageEdge;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.golemcore.bot.domain.service.StringValueSupport;

/**
 * Builds ordered artifact lineage projections from a candidate rollout history.
 */
@Service
public class ArtifactLineageProjectionService {

    private static final int PROJECTION_SCHEMA_VERSION = 1;

    private final Clock clock;

    public ArtifactLineageProjectionService(Clock clock) {
        this.clock = clock;
    }

    public ArtifactLineageProjection project(
            String artifactStreamId,
            EvolutionCandidate candidate,
            List<PromotionDecision> decisions,
            List<BenchmarkCampaign> campaigns) {
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate must not be null");
        }
        List<PromotionDecision> sortedDecisions = decisions != null
                ? decisions.stream()
                        .filter(decision -> decision != null)
                        .sorted(Comparator.comparing(PromotionDecision::getDecidedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList()
                : List.of();
        List<BenchmarkCampaign> benchmarkCampaigns = campaigns != null ? campaigns : List.of();

        List<ArtifactLineageNode> nodes = new ArrayList<>();
        List<ArtifactLineageEdge> edges = new ArrayList<>();
        String proposedNodeId = candidate.getId() + ":proposed";
        nodes.add(ArtifactLineageNode.builder()
                .nodeId(proposedNodeId)
                .contentRevisionId(candidate.getContentRevisionId())
                .lifecycleState("candidate")
                .rolloutStage("proposed")
                .originBundleId(candidate.getBaseVersion())
                .sourceRunIds(candidate.getSourceRunIds() != null ? candidate.getSourceRunIds() : List.of())
                .campaignIds(resolveCampaignIds(benchmarkCampaigns, candidate.getBaseVersion()))
                .attributionMode("bundle_observed")
                .createdAt(candidate.getCreatedAt())
                .build());

        String previousNodeId = proposedNodeId;
        for (PromotionDecision decision : sortedDecisions) {
            String nodeId = decision.getId() + ":" + decision.getToRolloutStage();
            nodes.add(ArtifactLineageNode.builder()
                    .nodeId(nodeId)
                    .contentRevisionId(decision.getContentRevisionId())
                    .lifecycleState(decision.getToLifecycleState())
                    .rolloutStage(decision.getToRolloutStage())
                    .promotionDecisionId(decision.getId())
                    .originBundleId(decision.getOriginBundleId())
                    .sourceRunIds(candidate.getSourceRunIds() != null ? candidate.getSourceRunIds() : List.of())
                    .campaignIds(resolveCampaignIds(benchmarkCampaigns, decision.getOriginBundleId()))
                    .attributionMode("bundle_observed")
                    .createdAt(decision.getDecidedAt())
                    .build());
            edges.add(ArtifactLineageEdge.builder()
                    .edgeId(previousNodeId + "->" + nodeId)
                    .fromNodeId(previousNodeId)
                    .toNodeId(nodeId)
                    .edgeType(resolveEdgeType(decision.getToRolloutStage()))
                    .createdAt(decision.getDecidedAt())
                    .build());
            previousNodeId = nodeId;
        }

        List<String> railOrder = nodes.stream().map(ArtifactLineageNode::getNodeId).toList();
        return ArtifactLineageProjection.builder()
                .artifactStreamId(artifactStreamId)
                .originArtifactStreamId(candidate.getOriginArtifactStreamId())
                .artifactKey(candidate.getArtifactKey())
                .nodes(nodes)
                .edges(edges)
                .railOrder(railOrder)
                .branches(List.of())
                .defaultSelectedNodeId(railOrder.getLast())
                .defaultSelectedRevisionId(nodes.getLast().getContentRevisionId())
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now(clock))
                .build();
    }

    private List<String> resolveCampaignIds(List<BenchmarkCampaign> campaigns, String bundleId) {
        if (StringValueSupport.isBlank(bundleId)) {
            return List.of();
        }
        return campaigns.stream()
                .filter(campaign -> campaign != null)
                .filter(campaign -> bundleId.equals(campaign.getBaselineBundleId())
                        || bundleId.equals(campaign.getCandidateBundleId()))
                .map(BenchmarkCampaign::getId)
                .toList();
    }

    private String resolveEdgeType(String rolloutStage) {
        return switch (rolloutStage) {
        case "replayed" -> "replayed_as";
        case "shadowed" -> "shadow_promoted";
        case "canary" -> "canary_promoted";
        case "approved" -> "approved_to_active";
        case "reverted" -> "reverted_from";
        default -> "derived_from";
        };
    }
}
