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

import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactLineageNode;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactNormalizedRevisionProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactRevisionDiffProjection;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactTransitionDiffProjection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.support.StringValueSupport;
import org.springframework.stereotype.Service;

/**
 * Computes semantic and raw diffs between artifact revisions and transitions.
 */
@Service
public class ArtifactDiffService {

    private static final int PROJECTION_SCHEMA_VERSION = 1;

    public ArtifactDiffService(ArtifactNormalizedRevisionProjectionService normalizedRevisionProjectionService) {
    }

    public ArtifactRevisionDiffProjection compareRevisions(
            String artifactStreamId,
            String artifactKey,
            ArtifactNormalizedRevisionProjection fromProjection,
            ArtifactNormalizedRevisionProjection toProjection,
            ArtifactImpactProjection impactProjection) {
        List<String> changedFields = new ArrayList<>();
        if (fromProjection == null || toProjection == null) {
            changedFields.add("missing_revision");
        } else if (!StringValueSupport.nullSafe(fromProjection.getNormalizedHash())
                .equals(StringValueSupport.nullSafe(toProjection.getNormalizedHash()))) {
            changedFields.add("normalizedContent");
        }
        List<String> semanticSections = toProjection != null ? toProjection.getSemanticSections() : List.of();
        String rawPatch = buildRawPatch(
                fromProjection != null ? fromProjection.getNormalizedContent() : "",
                toProjection != null ? toProjection.getNormalizedContent() : "");
        return ArtifactRevisionDiffProjection.builder()
                .artifactStreamId(artifactStreamId)
                .artifactKey(artifactKey)
                .fromRevisionId(fromProjection != null ? fromProjection.getContentRevisionId() : null)
                .toRevisionId(toProjection != null ? toProjection.getContentRevisionId() : null)
                .summary(changedFields.isEmpty() ? "No content changes" : "Artifact content changed")
                .semanticSections(semanticSections)
                .rawPatch(rawPatch)
                .changedFields(changedFields)
                .riskSignals(changedFields.isEmpty() ? List.of() : List.of("content_changed"))
                .impactSummary(impactProjection)
                .attributionMode(impactProjection != null ? impactProjection.getAttributionMode() : null)
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now())
                .build();
    }

    public ArtifactTransitionDiffProjection compareTransition(
            String artifactStreamId,
            String artifactKey,
            ArtifactLineageNode fromNode,
            ArtifactLineageNode toNode,
            ArtifactRevisionDiffProjection revisionDiff,
            ArtifactImpactProjection impactProjection) {
        boolean contentChanged = revisionDiff != null
                && revisionDiff.getChangedFields() != null
                && !revisionDiff.getChangedFields().isEmpty();
        if (fromNode != null
                && toNode != null
                && StringValueSupport.nullSafe(fromNode.getContentRevisionId())
                        .equals(StringValueSupport.nullSafe(toNode.getContentRevisionId()))
                && revisionDiff == null) {
            contentChanged = false;
        }
        return ArtifactTransitionDiffProjection.builder()
                .artifactStreamId(artifactStreamId)
                .artifactKey(artifactKey)
                .fromNodeId(fromNode != null ? fromNode.getNodeId() : null)
                .toNodeId(toNode != null ? toNode.getNodeId() : null)
                .fromRevisionId(fromNode != null ? fromNode.getContentRevisionId() : null)
                .toRevisionId(toNode != null ? toNode.getContentRevisionId() : null)
                .fromRolloutStage(fromNode != null ? fromNode.getRolloutStage() : null)
                .toRolloutStage(toNode != null ? toNode.getRolloutStage() : null)
                .contentChanged(contentChanged)
                .summary(contentChanged ? "Transition includes content change" : "Transition changes rollout only")
                .impactSummary(impactProjection)
                .attributionMode(impactProjection != null ? impactProjection.getAttributionMode() : null)
                .projectionSchemaVersion(PROJECTION_SCHEMA_VERSION)
                .projectedAt(Instant.now())
                .build();
    }

    private String buildRawPatch(String fromContent, String toContent) {
        return "--- from\n" + fromContent + "\n+++ to\n" + toContent;
    }
}
