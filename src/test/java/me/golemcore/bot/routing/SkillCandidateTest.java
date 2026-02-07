package me.golemcore.bot.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillCandidateTest {

    @Test
    void toPromptSummary_formatsCorrectly() {
        SkillCandidate candidate = SkillCandidate.builder()
                .name("code-review")
                .description("Review code for bugs and best practices")
                .semanticScore(0.89)
                .build();

        String summary = candidate.toPromptSummary();

        assertEquals("- **code-review** (score: 0.89): Review code for bugs and best practices", summary);
    }

    @Test
    void toPromptSummary_handlesLowScore() {
        SkillCandidate candidate = SkillCandidate.builder()
                .name("debug")
                .description("Find and fix bugs")
                .semanticScore(0.05)
                .build();

        String summary = candidate.toPromptSummary();

        assertEquals("- **debug** (score: 0.05): Find and fix bugs", summary);
    }

    @Test
    void builder_createsValidObject() {
        SkillCandidate candidate = SkillCandidate.builder()
                .name("refactor")
                .description("Improve code structure")
                .semanticScore(0.75)
                .build();

        assertEquals("refactor", candidate.getName());
        assertEquals("Improve code structure", candidate.getDescription());
        assertEquals(0.75, candidate.getSemanticScore());
    }
}
