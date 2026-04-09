package me.golemcore.bot.domain.model.selfevolving.tactic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TacticCatalogProjection {

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
    private Double score;
    private Double successRate;
    private Double benchmarkWinRate;
    @Builder.Default
    private List<String> regressionFlags = new ArrayList<>();
    private Double recencyScore;
    private Double golemLocalUsageSuccess;
    private String embeddingStatus;
    private Instant updatedAt;
    private TacticSearchExplanation explanation;
}
