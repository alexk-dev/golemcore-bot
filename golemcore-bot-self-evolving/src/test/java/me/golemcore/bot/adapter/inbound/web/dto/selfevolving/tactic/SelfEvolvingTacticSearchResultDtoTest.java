package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SelfEvolvingTacticSearchResultDtoTest {

    @Test
    void shouldRetainInheritedFieldsAndExplanation() {
        SelfEvolvingTacticSearchExplanationDto explanation = SelfEvolvingTacticSearchExplanationDto.builder()
                .searchMode("hybrid")
                .eligible(Boolean.TRUE)
                .matchedTerms(List.of("planner", "tier"))
                .finalScore(0.91)
                .build();

        SelfEvolvingTacticSearchResultDto result = SelfEvolvingTacticSearchResultDto.builder()
                .tacticId("tactic-1")
                .artifactStreamId("artifact-stream-1")
                .title("Planner tactic")
                .promotionState("active")
                .score(0.88)
                .explanation(explanation)
                .build();

        assertThat(result.getTacticId()).isEqualTo("tactic-1");
        assertThat(result.getArtifactStreamId()).isEqualTo("artifact-stream-1");
        assertThat(result.getTitle()).isEqualTo("Planner tactic");
        assertThat(result.getPromotionState()).isEqualTo("active");
        assertThat(result.getScore()).isEqualTo(0.88);
        assertThat(result.getExplanation()).isSameAs(explanation);
        assertThat(result.getExplanation().getMatchedTerms()).containsExactly("planner", "tier");
    }

    @Test
    void shouldSupportMutableScoreAndExplanation() {
        SelfEvolvingTacticSearchResultDto result = new SelfEvolvingTacticSearchResultDto();
        SelfEvolvingTacticSearchExplanationDto explanation = new SelfEvolvingTacticSearchExplanationDto();

        result.setScore(0.42);
        result.setExplanation(explanation);

        assertThat(result.getScore()).isEqualTo(0.42);
        assertThat(result.getExplanation()).isSameAs(explanation);
    }
}
