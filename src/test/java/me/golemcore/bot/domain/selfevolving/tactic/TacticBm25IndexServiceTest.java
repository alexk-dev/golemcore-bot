package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TacticBm25IndexServiceTest {

    private TacticBm25IndexService bm25IndexService;

    @BeforeEach
    void setUp() {
        bm25IndexService = new TacticBm25IndexService();
        bm25IndexService.replaceDocuments(List.of(
                TacticIndexDocument.builder()
                        .tacticId("planner")
                        .artifactStreamId("stream-planner")
                        .artifactKey("skill:planner")
                        .artifactType("skill")
                        .title("Planner tactic")
                        .lexicalText("planner task planning shell git benchmark")
                        .semanticText("planner")
                        .promotionState("approved")
                        .rolloutStage("approved")
                        .updatedAt(Instant.parse("2026-04-01T22:00:00Z"))
                        .build(),
                TacticIndexDocument.builder()
                        .tacticId("summarizer")
                        .artifactStreamId("stream-summarizer")
                        .artifactKey("skill:summarizer")
                        .artifactType("skill")
                        .title("Summarizer tactic")
                        .lexicalText("summarizer concise prose")
                        .semanticText("summarizer")
                        .promotionState("approved")
                        .rolloutStage("approved")
                        .updatedAt(Instant.parse("2026-04-01T21:00:00Z"))
                        .build()));
    }

    @Test
    void shouldPreferLexicallyRelevantTacticForExpandedQueryViews() {
        List<TacticBm25IndexService.ScoredDocument> results = bm25IndexService.search(
                TacticSearchQuery.builder()
                        .rawQuery("plan task with shell and git")
                        .queryViews(List.of("plan", "shell", "git"))
                        .build(),
                5);

        assertFalse(results.isEmpty());
        assertEquals("planner", results.getFirst().document().getTacticId());
    }
}
