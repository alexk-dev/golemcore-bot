package me.golemcore.bot.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillMatchResultTest {

    @Test
    void noMatch_createsResultWithNullSkill() {
        SkillMatchResult result = SkillMatchResult.noMatch("Test reason");

        assertNull(result.getSelectedSkill());
        assertEquals(1.0, result.getConfidence());
        assertEquals("fast", result.getModelTier());
        assertEquals("Test reason", result.getReason());
        assertFalse(result.hasMatch());
    }

    @Test
    void fromSemantic_createsResultFromCandidate() {
        SkillCandidate candidate = SkillCandidate.builder()
                .name("test-skill")
                .description("Test skill description")
                .semanticScore(0.92)
                .build();

        List<SkillCandidate> allCandidates = List.of(
                candidate,
                SkillCandidate.builder()
                        .name("other-skill")
                        .semanticScore(0.75)
                        .build());

        SkillMatchResult result = SkillMatchResult.fromSemantic(candidate, allCandidates);

        assertEquals("test-skill", result.getSelectedSkill());
        assertEquals(0.92, result.getConfidence());
        assertEquals("balanced", result.getModelTier());
        assertEquals("High-confidence semantic match", result.getReason());
        assertEquals(2, result.getCandidates().size());
        assertFalse(result.isLlmClassifierUsed());
        assertTrue(result.hasMatch());
    }

    @Test
    void hasMatch_returnsFalseForEmptySkill() {
        SkillMatchResult result = SkillMatchResult.builder()
                .selectedSkill("")
                .confidence(0.5)
                .build();

        assertFalse(result.hasMatch());
    }

    @Test
    void hasMatch_returnsTrueForValidSkill() {
        SkillMatchResult result = SkillMatchResult.builder()
                .selectedSkill("summarize")
                .confidence(0.8)
                .build();

        assertTrue(result.hasMatch());
    }

    @Test
    void builder_setsDefaultValues() {
        SkillMatchResult result = SkillMatchResult.builder()
                .selectedSkill("test")
                .build();

        assertFalse(result.isCached());
        assertFalse(result.isLlmClassifierUsed());
    }
}
