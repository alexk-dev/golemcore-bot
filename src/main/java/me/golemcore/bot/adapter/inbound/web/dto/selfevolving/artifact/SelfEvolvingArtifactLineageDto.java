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
public class SelfEvolvingArtifactLineageDto {

    private String artifactStreamId;
    private String originArtifactStreamId;
    private String artifactKey;

    @Builder.Default
    private List<NodeDto> nodes = new ArrayList<>();

    @Builder.Default
    private List<EdgeDto> edges = new ArrayList<>();

    @Builder.Default
    private List<String> railOrder = new ArrayList<>();

    @Builder.Default
    private List<String> branches = new ArrayList<>();

    private String defaultSelectedNodeId;
    private String defaultSelectedRevisionId;
    private Integer projectionSchemaVersion;
    private String projectedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeDto {

        private String nodeId;
        private String contentRevisionId;
        private String lifecycleState;
        private String rolloutStage;
        private String promotionDecisionId;
        private String originBundleId;

        @Builder.Default
        private List<String> sourceRunIds = new ArrayList<>();

        @Builder.Default
        private List<String> campaignIds = new ArrayList<>();

        private String attributionMode;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EdgeDto {

        private String edgeId;
        private String fromNodeId;
        private String toNodeId;
        private String edgeType;
        private String createdAt;
    }
}
