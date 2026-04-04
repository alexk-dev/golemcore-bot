package me.golemcore.bot.adapter.inbound.web.dto.selfevolving;

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
public class SelfEvolvingCandidateDto {
    private String id;
    private String goal;
    private String artifactType;
    private String artifactStreamId;
    private String artifactKey;
    private String status;
    private String riskLevel;
    private String expectedImpact;
    private String proposedDiff;

    @Builder.Default
    private List<String> sourceRunIds = new ArrayList<>();

    @Builder.Default
    private List<EvidenceRefDto> evidenceRefs = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceRefDto {
        private String traceId;
        private String spanId;
        private String outputFragment;
    }
}
