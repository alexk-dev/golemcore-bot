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
public class SelfEvolvingArtifactEvidenceDto {

    private String artifactStreamId;
    private String artifactKey;
    private String payloadKind;
    private String revisionId;
    private String fromRevisionId;
    private String toRevisionId;
    private String fromNodeId;
    private String toNodeId;

    @Builder.Default
    private List<String> runIds = new ArrayList<>();

    @Builder.Default
    private List<String> traceIds = new ArrayList<>();

    @Builder.Default
    private List<String> spanIds = new ArrayList<>();

    @Builder.Default
    private List<String> campaignIds = new ArrayList<>();

    @Builder.Default
    private List<String> promotionDecisionIds = new ArrayList<>();

    @Builder.Default
    private List<String> approvalRequestIds = new ArrayList<>();

    @Builder.Default
    private List<String> findings = new ArrayList<>();

    private Integer projectionSchemaVersion;
    private String projectedAt;
}
