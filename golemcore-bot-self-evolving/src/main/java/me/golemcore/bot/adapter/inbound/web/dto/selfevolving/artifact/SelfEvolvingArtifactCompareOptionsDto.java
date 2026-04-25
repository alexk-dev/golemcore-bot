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
public class SelfEvolvingArtifactCompareOptionsDto {

    private String artifactStreamId;
    private String defaultFromRevisionId;
    private String defaultToRevisionId;
    private String defaultFromNodeId;
    private String defaultToNodeId;

    @Builder.Default
    private List<CompareOptionDto> revisionOptions = new ArrayList<>();

    @Builder.Default
    private List<CompareOptionDto> transitionOptions = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompareOptionDto {

        private String label;
        private String fromId;
        private String toId;
    }
}
