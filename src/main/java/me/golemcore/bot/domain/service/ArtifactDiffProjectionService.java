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

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionRecord;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Orchestrates revision and transition diff projections from artifact lookup
 * data.
 */
@Service
public class ArtifactDiffProjectionService {

    private final ArtifactProjectionLookupService artifactProjectionLookupService;
    private final BenchmarkLabService benchmarkLabService;
    private final ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService;
    private final ArtifactDiffService artifactDiffService;
    private final ArtifactImpactService artifactImpactService;

    public ArtifactDiffProjectionService(
            ArtifactProjectionLookupService artifactProjectionLookupService,
            BenchmarkLabService benchmarkLabService,
            ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService,
            ArtifactDiffService artifactDiffService,
            ArtifactImpactService artifactImpactService) {
        this.artifactProjectionLookupService = artifactProjectionLookupService;
        this.benchmarkLabService = benchmarkLabService;
        this.normalizedRevisionProjectionService = normalizedRevisionProjectionService;
        this.artifactDiffService = artifactDiffService;
        this.artifactImpactService = artifactImpactService;
    }

    public ArtifactRevisionDiffProjection getRevisionDiff(String artifactStreamId, String fromRevisionId,
            String toRevisionId) {
        ArtifactRevisionRecord fromRevision = artifactProjectionLookupService.findRevision(artifactStreamId,
                fromRevisionId)
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + fromRevisionId));
        ArtifactRevisionRecord toRevision = artifactProjectionLookupService.findRevision(artifactStreamId, toRevisionId)
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + toRevisionId));
        ArtifactImpactProjection impactProjection = artifactImpactService.summarizeImpact(
                artifactStreamId,
                fromRevisionId,
                toRevisionId,
                artifactProjectionLookupService.findBundleByRevision(artifactStreamId, fromRevisionId).orElse(null),
                artifactProjectionLookupService.findBundleByRevision(artifactStreamId, toRevisionId).orElse(null),
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

    public ArtifactTransitionDiffProjection getTransitionDiff(String artifactStreamId,
            ArtifactLineageProjection lineage,
            String fromNodeId, String toNodeId) {
        ArtifactLineageNode fromNode = findRequiredNode(lineage, fromNodeId);
        ArtifactLineageNode toNode = findRequiredNode(lineage, toNodeId);

        ArtifactRevisionDiffProjection revisionDiff = null;
        if (!StringValueSupport.nullSafe(fromNode.getContentRevisionId())
                .equals(StringValueSupport.nullSafe(toNode.getContentRevisionId()))) {
            revisionDiff = getRevisionDiff(artifactStreamId, fromNode.getContentRevisionId(),
                    toNode.getContentRevisionId());
        } else {
            String transitionBaseRevisionId = artifactProjectionLookupService.resolveTransitionBaseRevisionId(toNode);
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
                artifactProjectionLookupService.findBundleByRevision(artifactStreamId, fromNode.getContentRevisionId())
                        .orElse(null),
                artifactProjectionLookupService.findBundleByRevision(artifactStreamId, toNode.getContentRevisionId())
                        .orElse(null),
                benchmarkLabService.getCampaigns(),
                Set.of());
        return artifactDiffService.compareTransition(
                artifactStreamId,
                artifactProjectionLookupService.findRevision(artifactStreamId, toNode.getContentRevisionId())
                        .map(ArtifactRevisionRecord::getArtifactKey)
                        .orElse(null),
                fromNode,
                toNode,
                revisionDiff,
                impactProjection);
    }

    private ArtifactLineageNode findRequiredNode(ArtifactLineageProjection lineage, String nodeId) {
        return lineage.getNodes().stream()
                .filter(node -> node != null && nodeId.equals(node.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
    }
}
