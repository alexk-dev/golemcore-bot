package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingArtifactTransitionDiffDto {

    private String artifactStreamId;
    private String artifactKey;
    private String fromNodeId;
    private String toNodeId;
    private String fromRevisionId;
    private String toRevisionId;
    private String fromRolloutStage;
    private String toRolloutStage;
    private boolean contentChanged;
    private String summary;
    private ArtifactImpactProjection impactSummary;
    private String attributionMode;
    private Integer projectionSchemaVersion;
    private String projectedAt;
}
