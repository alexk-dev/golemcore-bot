package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Search result DTO carrying tactic detail plus explanation.
 */
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SelfEvolvingTacticSearchResultDto extends SelfEvolvingTacticDto {

    private Double score;
    private SelfEvolvingTacticSearchExplanationDto explanation;

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public SelfEvolvingTacticSearchExplanationDto getExplanation() {
        return explanation;
    }

    public void setExplanation(SelfEvolvingTacticSearchExplanationDto explanation) {
        this.explanation = explanation;
    }
}
