package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.golemcore.bot.domain.model.selfevolving.artifact.ArtifactImpactProjection;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingArtifactRevisionDiffDto {

    private String artifactStreamId;
    private String artifactKey;
    private String fromRevisionId;
    private String toRevisionId;
    private String summary;

    @Builder.Default
    private List<String> semanticSections = new ArrayList<>();

    private String rawPatch;

    @Builder.Default
    private List<String> changedFields = new ArrayList<>();

    @Builder.Default
    private List<String> riskSignals = new ArrayList<>();

    private ArtifactImpactProjection impactSummary;
    private String attributionMode;
    private Integer projectionSchemaVersion;
    private String projectedAt;
}
