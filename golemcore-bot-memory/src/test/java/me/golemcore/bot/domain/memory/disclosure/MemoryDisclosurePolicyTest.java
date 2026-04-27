package me.golemcore.bot.domain.memory.disclosure;

import me.golemcore.bot.domain.memory.model.MemoryDisclosureInput;
import me.golemcore.bot.domain.memory.model.MemoryDisclosureMode;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import me.golemcore.bot.domain.memory.model.MemoryPromptStyle;
import me.golemcore.bot.domain.memory.model.MemorySelectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryDisclosurePolicyTest {

    private final MemoryDisclosurePolicy memoryDisclosurePolicy = new MemoryDisclosurePolicy();

    @Test
    void shouldReturnIndexAndHintsOnlyForIndexMode() {
        List<MemoryPackSectionType> sections = memoryDisclosurePolicy
                .determineSections(input(MemoryDisclosureMode.INDEX, true));

        assertEquals(List.of(MemoryPackSectionType.INDEX, MemoryPackSectionType.FOLLOWUP_HINTS), sections);
    }

    @Test
    void shouldIncludeDetailSectionForSelectiveDetailMode() {
        List<MemoryPackSectionType> sections = memoryDisclosurePolicy
                .determineSections(input(MemoryDisclosureMode.SELECTIVE_DETAIL, true));

        assertEquals(List.of(MemoryPackSectionType.WORKING_MEMORY, MemoryPackSectionType.EPISODIC_MEMORY,
                MemoryPackSectionType.SEMANTIC_MEMORY, MemoryPackSectionType.PROCEDURAL_MEMORY,
                MemoryPackSectionType.DETAIL_SNIPPETS, MemoryPackSectionType.FOLLOWUP_HINTS), sections);
    }

    @Test
    void shouldNotExposeHintsInFullPackMode() {
        List<MemoryPackSectionType> sections = memoryDisclosurePolicy
                .determineSections(input(MemoryDisclosureMode.FULL_PACK, true));

        assertEquals(List.of(MemoryPackSectionType.WORKING_MEMORY, MemoryPackSectionType.EPISODIC_MEMORY,
                MemoryPackSectionType.SEMANTIC_MEMORY, MemoryPackSectionType.PROCEDURAL_MEMORY), sections);
    }

    private MemoryDisclosureInput input(MemoryDisclosureMode mode, boolean hintsEnabled) {
        return MemoryDisclosureInput.builder().selectionResult(MemorySelectionResult.builder().build())
                .disclosureMode(mode).promptStyle(MemoryPromptStyle.BALANCED).toolExpansionEnabled(true)
                .disclosureHintsEnabled(hintsEnabled).detailMinScore(0.8).build();
    }
}
