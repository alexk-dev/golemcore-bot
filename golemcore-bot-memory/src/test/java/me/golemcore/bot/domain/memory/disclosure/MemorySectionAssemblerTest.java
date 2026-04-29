package me.golemcore.bot.domain.memory.disclosure;

import me.golemcore.bot.domain.memory.model.MemoryDisclosureMode;
import me.golemcore.bot.domain.memory.model.MemoryDisclosurePlan;
import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import me.golemcore.bot.domain.memory.model.MemoryPromptStyle;
import me.golemcore.bot.domain.memory.model.MemorySelectionResult;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySectionAssemblerTest {

    private final MemorySectionAssembler memorySectionAssembler = new MemorySectionAssembler();

    @Test
    void shouldBuildIndexAndFollowupHintSections() {
        List<MemoryPackSection> sections = memorySectionAssembler.assemble(MemoryDisclosurePlan.builder()
                .selectionResult(MemorySelectionResult.builder()
                        .selectedCandidates(List.of(scored("w1", MemoryItem.Layer.WORKING, 0.9, "Working"),
                                scored("s1", MemoryItem.Layer.SEMANTIC, 0.8, "Semantic")))
                        .build())
                .disclosureMode(MemoryDisclosureMode.INDEX).promptStyle(MemoryPromptStyle.BALANCED)
                .toolExpansionEnabled(true)
                .sectionTypes(List.of(MemoryPackSectionType.INDEX, MemoryPackSectionType.FOLLOWUP_HINTS)).build());

        assertEquals(2, sections.size());
        assertEquals(MemoryPackSectionType.INDEX, sections.get(0).getType());
        assertTrue(sections.get(0).getLines().contains("Working memory available: 1"));
        assertTrue(sections.get(0).getLines().contains("Relevant facts available: 1"));
        assertEquals(MemoryPackSectionType.FOLLOWUP_HINTS, sections.get(1).getType());
    }

    @Test
    void shouldIncludeOnlyHighConfidenceCandidatesInDetailSection() {
        List<MemoryPackSection> sections = memorySectionAssembler.assemble(MemoryDisclosurePlan.builder()
                .selectionResult(MemorySelectionResult.builder()
                        .selectedCandidates(List.of(scored("s1", MemoryItem.Layer.SEMANTIC, 0.95, "High"),
                                scored("s2", MemoryItem.Layer.SEMANTIC, 0.60, "Low")))
                        .build())
                .disclosureMode(MemoryDisclosureMode.SELECTIVE_DETAIL).promptStyle(MemoryPromptStyle.BALANCED)
                .detailMinScore(0.90).sectionTypes(List.of(MemoryPackSectionType.DETAIL_SNIPPETS)).build());

        assertEquals(1, sections.size());
        assertEquals(MemoryPackSectionType.DETAIL_SNIPPETS, sections.get(0).getType());
        assertEquals(1, sections.get(0).getLines().size());
        assertTrue(sections.get(0).getLines().get(0).contains("High"));
    }

    private MemoryScoredItem scored(String id, MemoryItem.Layer layer, double score, String title) {
        return MemoryScoredItem.builder().score(score).item(MemoryItem.builder().id(id).layer(layer)
                .type(MemoryItem.Type.PROJECT_FACT).title(title).content(title + " content").build()).build();
    }
}
