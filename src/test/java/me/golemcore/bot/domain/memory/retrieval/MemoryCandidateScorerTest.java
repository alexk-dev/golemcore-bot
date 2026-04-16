package me.golemcore.bot.domain.memory.retrieval;

import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCandidateScorerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-04-16T00:00:00Z");

    private final MemoryCandidateScorer scorer = new MemoryCandidateScorer();

    @Test
    void shouldApplySkillTagBoostCaseInsensitively() {
        MemoryRetrievalPlan plan = plan("code review", "QA-Review");
        MemoryItem matchingSkill = item("matching-skill", "code review checklist", List.of("qa-review"));
        MemoryItem unrelatedSkill = item("unrelated-skill", "code review checklist", List.of("docs"));

        List<MemoryScoredItem> scored = scorer.score(plan, List.of(unrelatedSkill, matchingSkill));

        assertEquals(List.of("matching-skill", "unrelated-skill"),
                scored.stream().map(candidate -> candidate.getItem().getId()).toList());
        assertTrue(scored.get(0).getScore() > scored.get(1).getScore());
    }

    @Test
    void shouldUseUpdatedAtAsTieBreakerWhenScoresAreEqual() {
        MemoryRetrievalPlan plan = plan("release notes", null);
        MemoryItem older = item("older", "release notes", List.of());
        older.setUpdatedAt(Instant.parse("2026-04-15T00:00:00Z"));
        MemoryItem newer = item("newer", "release notes", List.of());
        newer.setUpdatedAt(Instant.parse("2026-04-16T00:00:00Z"));

        List<MemoryScoredItem> scored = scorer.score(plan, List.of(older, newer));

        assertEquals(List.of("newer", "older"),
                scored.stream().map(candidate -> candidate.getItem().getId()).toList());
    }

    private MemoryRetrievalPlan plan(String queryText, String activeSkill) {
        return MemoryRetrievalPlan.builder()
                .query(MemoryQuery.builder()
                        .queryText(queryText)
                        .activeSkill(activeSkill)
                        .build())
                .build();
    }

    private MemoryItem item(String id, String content, List<String> tags) {
        return MemoryItem.builder()
                .id(id)
                .type(MemoryItem.Type.PROJECT_FACT)
                .content(content)
                .tags(tags)
                .confidence(0.80)
                .salience(0.70)
                .createdAt(TIMESTAMP)
                .updatedAt(TIMESTAMP)
                .build();
    }
}
