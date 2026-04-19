package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Detailed tactic DTO for bot workspace and inspection views.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingTacticDto {

    private String tacticId;
    private String artifactStreamId;
    private String originArtifactStreamId;
    private String artifactKey;
    private String artifactType;
    private String title;
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    private String contentRevisionId;
    private String intentSummary;
    private String behaviorSummary;
    private String toolSummary;
    private String outcomeSummary;
    private String benchmarkSummary;
    private String approvalNotes;
    @Builder.Default
    private List<String> evidenceSnippets = new ArrayList<>();
    @Builder.Default
    private List<String> taskFamilies = new ArrayList<>();
    @Builder.Default
    private List<String> tags = new ArrayList<>();
    private String promotionState;
    private String rolloutStage;
    private Double successRate;
    private Double benchmarkWinRate;
    @Builder.Default
    private List<String> regressionFlags = new ArrayList<>();
    private Double recencyScore;
    private Double golemLocalUsageSuccess;
    private String embeddingStatus;
    private String updatedAt;
}
