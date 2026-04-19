package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingArtifactWorkspaceSummaryDto {

    private String artifactStreamId;
    private String originArtifactStreamId;
    private String artifactKey;

    @Builder.Default
    private List<String> artifactAliases = new ArrayList<>();

    private String artifactType;
    private String artifactSubtype;
    private String activeRevisionId;
    private String latestCandidateRevisionId;
    private String currentLifecycleState;
    private String currentRolloutStage;
    private Integer campaignCount;
    private Integer projectionSchemaVersion;
    private String updatedAt;
    private String projectedAt;
    private SelfEvolvingArtifactCompareOptionsDto compareOptions;
}
