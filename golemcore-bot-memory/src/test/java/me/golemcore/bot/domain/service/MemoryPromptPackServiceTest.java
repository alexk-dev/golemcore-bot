package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.memory.disclosure.MemoryDisclosurePlanner;
import me.golemcore.bot.domain.memory.disclosure.MemoryDisclosurePolicy;
import me.golemcore.bot.domain.memory.disclosure.MemoryPackRenderer;
import me.golemcore.bot.domain.memory.disclosure.MemorySectionAssembler;
import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryPromptPackServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryPromptPackService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.getMemoryDisclosureMode()).thenReturn("summary");
        when(runtimeConfigService.getMemoryPromptStyle()).thenReturn("balanced");
        when(runtimeConfigService.isMemoryToolExpansionEnabled()).thenReturn(true);
        when(runtimeConfigService.isMemoryDisclosureHintsEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemoryDetailMinScore()).thenReturn(0.80);

        service = new MemoryPromptPackService(runtimeConfigService,
                new MemoryDisclosurePlanner(new MemoryDisclosurePolicy()), new MemorySectionAssembler(),
                new MemoryPackRenderer());
    }

    @Test
    void shouldReturnEmptyPackWhenCandidatesMissing() {
        MemoryPack pack = service.build(MemoryQuery.builder().build(), List.of());

        assertTrue(pack.getItems().isEmpty());
        assertTrue(pack.getSections().isEmpty());
        assertEquals("summary", pack.getDisclosureMode());
        assertEquals("", pack.getRenderedContext());
        assertEquals(0, asInt(pack.getDiagnostics(), "candidateCount"));
        assertEquals(0, asInt(pack.getDiagnostics(), "selectedCount"));
    }

    @Test
    void shouldSelectItemsBySoftAndMaxBudgetWithHighScoreOverflow() {
        MemoryQuery query = MemoryQuery.builder().softPromptBudgetTokens(30).maxPromptBudgetTokens(50).build();

        MemoryScoredItem first = scored("id-1", "A", "short", MemoryItem.Layer.SEMANTIC, 0.90);
        MemoryScoredItem lowScoreOverflow = scored("id-2", "B", "short", MemoryItem.Layer.SEMANTIC, 0.60);
        MemoryScoredItem highScoreOverflow = scored("id-3", "C", "short", MemoryItem.Layer.PROCEDURAL, 0.95);
        MemoryScoredItem overMax = scored("id-4", "D", "short", MemoryItem.Layer.PROCEDURAL, 0.99);

        MemoryPack pack = service.build(query, List.of(first, lowScoreOverflow, highScoreOverflow, overMax));

        assertEquals(2, pack.getItems().size());
        assertEquals(List.of("id-1", "id-3"), pack.getItems().stream().map(MemoryItem::getId).toList());
        assertEquals(4, asInt(pack.getDiagnostics(), "candidateCount"));
        assertEquals(2, asInt(pack.getDiagnostics(), "selectedCount"));
        assertEquals(2, asInt(pack.getDiagnostics(), "droppedByBudget"));
        assertEquals(40, asInt(pack.getDiagnostics(), "estimatedTokens"));
        assertEquals(30, asInt(pack.getDiagnostics(), "softBudgetTokens"));
        assertEquals(50, asInt(pack.getDiagnostics(), "maxBudgetTokens"));
    }

    @Test
    void shouldRenderFullPackLayersInCanonicalOrderWhenConfigured() {
        when(runtimeConfigService.getMemoryDisclosureMode()).thenReturn("full_pack");

        String longContent = "x".repeat(500) + "\nline2";
        List<MemoryScoredItem> candidates = List.of(
                scored("semantic", "Semantic", "semantic content", MemoryItem.Layer.SEMANTIC, 1.0),
                scored("working", "Working", "working content", MemoryItem.Layer.WORKING, 1.0),
                scored("procedural", "Procedural", "procedural content", MemoryItem.Layer.PROCEDURAL, 1.0),
                scored("episodic", "Episodic", "episodic content", MemoryItem.Layer.EPISODIC, 1.0),
                MemoryScoredItem.builder().score(1.0).item(MemoryItem.builder().id("fallback").layer(null).type(null)
                        .title(" ").content(longContent).build()).build());

        MemoryQuery query = MemoryQuery.builder().softPromptBudgetTokens(2000).maxPromptBudgetTokens(2000).build();
        MemoryPack pack = service.build(query, candidates);
        String rendered = pack.getRenderedContext();

        assertTrue(rendered.contains("## Working Memory"));
        assertTrue(rendered.contains("## Episodic Memory"));
        assertTrue(rendered.contains("## Semantic Memory"));
        assertTrue(rendered.contains("## Procedural Memory"));
        assertTrue(rendered.indexOf("## Working Memory") < rendered.indexOf("## Episodic Memory"));
        assertTrue(rendered.indexOf("## Episodic Memory") < rendered.indexOf("## Semantic Memory"));
        assertTrue(rendered.indexOf("## Semantic Memory") < rendered.indexOf("## Procedural Memory"));
        assertTrue(rendered.contains("- [ITEM]"));
        assertTrue(rendered.contains("..."));
        assertFalse(rendered.contains("\nline2"));
    }

    @Test
    void shouldNormalizeInvalidBudgetsAndAlignMaxToSoft() {
        MemoryQuery query = MemoryQuery.builder().softPromptBudgetTokens(-10).maxPromptBudgetTokens(100).build();
        MemoryScoredItem candidate = scored("id-1", "A", "content", MemoryItem.Layer.SEMANTIC, 0.2);

        MemoryPack pack = service.build(query, List.of(candidate));

        assertEquals(1800, asInt(pack.getDiagnostics(), "softBudgetTokens"));
        assertEquals(1800, asInt(pack.getDiagnostics(), "maxBudgetTokens"));
    }

    @Test
    void shouldExposeSummarySectionsAndDisclosureMetadata() {
        MemoryPack pack = service.build(
                MemoryQuery.builder().softPromptBudgetTokens(2000).maxPromptBudgetTokens(2000).build(),
                List.of(scored("s-1", "Cache Policy", "Use redis for hot reads", MemoryItem.Layer.SEMANTIC, 0.91),
                        scored("p-1", "Recovery", "Restart cache worker after schema drift",
                                MemoryItem.Layer.PROCEDURAL, 0.93)));

        assertEquals("summary", pack.getDisclosureMode());
        Set<MemoryPackSectionType> sectionTypes = pack.getSections().stream().map(MemoryPackSection::getType)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(sectionTypes.contains(MemoryPackSectionType.SEMANTIC_MEMORY));
        assertTrue(sectionTypes.contains(MemoryPackSectionType.PROCEDURAL_MEMORY));
        assertTrue(sectionTypes.contains(MemoryPackSectionType.FOLLOWUP_HINTS));
        assertTrue(pack.getRenderedContext().contains("## Relevant Facts"));
        assertTrue(pack.getRenderedContext().contains("## Applicable Procedures"));
        assertEquals("summary", pack.getDiagnostics().get("disclosureMode"));
    }

    @Test
    void shouldIncludeDetailSnippetsOnlyForHighConfidenceItemsInSelectiveDetailMode() {
        when(runtimeConfigService.getMemoryDisclosureMode()).thenReturn("selective_detail");
        when(runtimeConfigService.getMemoryDetailMinScore()).thenReturn(0.90);

        MemoryPack pack = service.build(
                MemoryQuery.builder().softPromptBudgetTokens(2000).maxPromptBudgetTokens(2000).build(),
                List.of(scored("s-1", "High", "high confidence fact", MemoryItem.Layer.SEMANTIC, 0.95),
                        scored("s-2", "Low", "low confidence fact", MemoryItem.Layer.SEMANTIC, 0.82)));

        MemoryPackSection detailSection = pack.getSections().stream()
                .filter(section -> section.getType() == MemoryPackSectionType.DETAIL_SNIPPETS).findFirst()
                .orElseThrow();

        assertEquals(1, detailSection.getLines().size());
        assertTrue(detailSection.getLines().get(0).contains("High"));
        assertFalse(detailSection.getLines().get(0).contains("Low"));
    }

    private int asInt(Map<String, Object> diagnostics, String key) {
        Object value = diagnostics.get(key);
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private MemoryScoredItem scored(String id, String title, String content, MemoryItem.Layer layer, double score) {
        return MemoryScoredItem.builder().score(score).item(MemoryItem.builder().id(id).layer(layer)
                .type(MemoryItem.Type.PROJECT_FACT).title(title).content(content).build()).build();
    }
}
