package me.golemcore.bot.domain.memory.diagnostics;

import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDiagnosticsAssemblerTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryDiagnosticsAssembler memoryDiagnosticsAssembler;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getMemoryDiagnosticsVerbosity()).thenReturn("basic");
        memoryDiagnosticsAssembler = new MemoryDiagnosticsAssembler(runtimeConfigService);
    }

    @Test
    void shouldBuildBasicPromptDiagnosticsFromPromptPackAndStructuredCandidates() {
        MemoryPack pack = MemoryPack.builder().items(List.of(memoryItem("sem-1", MemoryItem.Layer.SEMANTIC)))
                .sections(List.of(MemoryPackSection.builder().type(MemoryPackSectionType.SEMANTIC_MEMORY)
                        .title("Relevant Facts").lines(List.of("line")).build()))
                .disclosureMode("summary").diagnostics(Map.of("selectedCount", 1, "sectionCount", 1))
                .renderedContext("## Relevant Facts").build();

        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildPromptDiagnostics(
                MemoryQuery.builder().scope("task:build-cache").scopeChain(List.of("task:build-cache", "global"))
                        .softPromptBudgetTokens(1200).maxPromptBudgetTokens(1600).build(),
                List.of(scored("sem-1", MemoryItem.Layer.SEMANTIC, 0.91),
                        scored("ep-1", MemoryItem.Layer.EPISODIC, 0.82)),
                pack);

        assertEquals(Boolean.TRUE, diagnostics.get("enabled"));
        assertEquals(2, diagnostics.get("structuredCandidates"));
        assertEquals(1, diagnostics.get("selectedCount"));
        assertEquals("summary", diagnostics.get("disclosureMode"));
        assertFalse(diagnostics.containsKey("scopeChain"));
    }

    @Test
    void shouldReturnOnlyEnabledFlagWhenVerbosityOff() {
        when(runtimeConfigService.getMemoryDiagnosticsVerbosity()).thenReturn("off");

        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildPromptDiagnostics(
                MemoryQuery.builder().scope("global").build(),
                List.of(scored("sem-1", MemoryItem.Layer.SEMANTIC, 0.91)),
                MemoryPack.builder().diagnostics(Map.of("selectedCount", 1)).build());

        assertEquals(Map.of("enabled", true), diagnostics);
    }

    @Test
    void shouldIncludeQueryAndSectionDetailsWhenVerbosityDetailed() {
        when(runtimeConfigService.getMemoryDiagnosticsVerbosity()).thenReturn("detailed");

        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildPromptDiagnostics(
                MemoryQuery.builder().scope("task:build-cache").scopeChain(List.of("task:build-cache", "global"))
                        .softPromptBudgetTokens(1000).maxPromptBudgetTokens(1800).workingTopK(1).episodicTopK(2)
                        .semanticTopK(3).proceduralTopK(4).build(),
                List.of(scored("sem-1", MemoryItem.Layer.SEMANTIC, 0.91)),
                MemoryPack.builder()
                        .sections(List.of(MemoryPackSection.builder().type(MemoryPackSectionType.SEMANTIC_MEMORY)
                                .title("Relevant Facts").lines(List.of("line")).build()))
                        .diagnostics(Map.of("selectedCount", 1, "sectionCount", 1)).build());

        assertEquals("task:build-cache", diagnostics.get("queryScope"));
        assertEquals(List.of("task:build-cache", "global"), diagnostics.get("scopeChain"));
        assertEquals(1000, diagnostics.get("softPromptBudgetTokens"));
        assertEquals(1800, diagnostics.get("maxPromptBudgetTokens"));
        assertEquals(1, diagnostics.get("workingTopK"));
        assertEquals(3, diagnostics.get("semanticTopK"));
        assertEquals(1, diagnostics.get("sectionCount"));
        assertTrue(diagnostics.containsKey("sectionTypes"));
    }

    @Test
    void shouldReturnDisabledPromptDiagnosticsAsEnabledFalseMap() {
        // Pins buildDisabledPromptDiagnostics so PIT cannot replace the
        // `{enabled=false}` map with an empty one.
        assertEquals(Map.of("enabled", false), memoryDiagnosticsAssembler.buildDisabledPromptDiagnostics());
    }

    @Test
    void shouldDefaultToBasicVerbosityWhenConfigIsBlank() {
        // Exercises the `basic` default branch of normalizeVerbosity — with
        // blank config the assembler still emits the full structured diagnostics
        // map (enabled, structuredCandidates, disclosureMode, etc).
        when(runtimeConfigService.getMemoryDiagnosticsVerbosity()).thenReturn("   ");

        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildPromptDiagnostics(
                MemoryQuery.builder().scope("global").build(),
                List.of(scored("sem-1", MemoryItem.Layer.SEMANTIC, 0.91)),
                MemoryPack.builder().disclosureMode("summary").diagnostics(Map.of("selectedCount", 1)).build());

        assertEquals(Boolean.TRUE, diagnostics.get("enabled"));
        assertEquals(1, diagnostics.get("structuredCandidates"));
        assertEquals("summary", diagnostics.get("disclosureMode"));
        assertFalse(diagnostics.containsKey("queryScope"), "basic verbosity must not include detailed query fields");
    }

    @Test
    void shouldDefaultToBasicVerbosityWhenConfigIsNull() {
        // Second branch of normalizeVerbosity — null config should behave
        // identically to blank config.
        when(runtimeConfigService.getMemoryDiagnosticsVerbosity()).thenReturn(null);

        Map<String, Object> diagnostics = memoryDiagnosticsAssembler.buildPromptDiagnostics(
                MemoryQuery.builder().scope("global").build(), List.of(), MemoryPack.builder().build());

        assertEquals(Boolean.TRUE, diagnostics.get("enabled"));
        assertFalse(diagnostics.containsKey("queryScope"));
    }

    private MemoryScoredItem scored(String id, MemoryItem.Layer layer, double score) {
        return MemoryScoredItem.builder().score(score).item(memoryItem(id, layer)).build();
    }

    private MemoryItem memoryItem(String id, MemoryItem.Layer layer) {
        return MemoryItem.builder().id(id).layer(layer).type(MemoryItem.Type.PROJECT_FACT).content("content").build();
    }
}
