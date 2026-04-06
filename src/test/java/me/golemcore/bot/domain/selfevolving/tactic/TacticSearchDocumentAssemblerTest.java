package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.selfevolving.tactic.TacticIndexDocument;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticSearchDocumentAssemblerTest {

    private final TacticSearchDocumentAssembler assembler = new TacticSearchDocumentAssembler();

    @Test
    void shouldBuildLexicalDocumentFromSearchableTacticFields() {
        TacticIndexDocument document = assembler.assemble(TacticRecord.builder()
                .tacticId("tactic-1")
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner tactic")
                .aliases(List.of("planner", "project planner"))
                .intentSummary("Plan and sequence delivery work")
                .behaviorSummary("Produces step-by-step execution plans")
                .toolSummary("shell git")
                .outcomeSummary("Lower failure rate for multi-step work")
                .benchmarkSummary("Won benchmark campaign 8/10")
                .approvalNotes("Safe for active routing")
                .evidenceSnippets(List.of("Used git diff before applying changes"))
                .taskFamilies(List.of("delivery", "planning"))
                .tags(List.of("planner", "skill"))
                .promotionState("approved")
                .rolloutStage("approved")
                .updatedAt(Instant.parse("2026-04-01T22:00:00Z"))
                .build());

        assertEquals("tactic-1", document.getTacticId());
        assertTrue(document.getLexicalText().contains("planner tactic"));
        assertTrue(document.getLexicalText().contains("project planner"));
        assertTrue(document.getLexicalText().contains("shell git"));
        assertTrue(document.getLexicalText().contains("won benchmark campaign 8/10"));
        assertTrue(document.getSemanticText().contains("[behavior] produces step-by-step execution plans"));
        assertTrue(document.getSemanticText().contains("[intent] plan and sequence delivery work"));
        assertTrue(document.getSemanticText().contains("[tooling] shell git"));
        assertTrue(document.getSemanticText().contains("[outcome] lower failure rate for multi-step work"));
        assertTrue(document.getSemanticText().contains("[benchmark] won benchmark campaign 8/10"));
        assertTrue(document.getSemanticText().contains("[evidence] used git diff before applying changes"));
    }
}
