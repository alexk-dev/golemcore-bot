package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Explainability payload for one tactic-search result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingTacticSearchExplanationDto {

    private String searchMode;
    private String degradedReason;
    private Double bm25Score;
    private Double vectorScore;
    private Double rrfScore;
    private Double qualityPrior;
    private Double mmrDiversityAdjustment;
    private Double negativeMemoryPenalty;
    private Double personalizationBoost;
    @Builder.Default
    private List<String> matchedQueryViews = new ArrayList<>();
    @Builder.Default
    private List<String> matchedTerms = new ArrayList<>();
    private Boolean eligible;
    private String gatingReason;
    private Double finalScore;
}
