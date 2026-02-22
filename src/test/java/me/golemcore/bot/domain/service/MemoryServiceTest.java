package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryWriteService memoryWriteService;
    private MemoryRetrievalService memoryRetrievalService;
    private MemoryPromptPackService memoryPromptPackService;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryWriteService = mock(MemoryWriteService.class);
        memoryRetrievalService = mock(MemoryRetrievalService.class);
        memoryPromptPackService = mock(MemoryPromptPackService.class);

        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(8);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(4);

        memoryService = new MemoryService(
                runtimeConfigService,
                memoryWriteService,
                memoryRetrievalService,
                memoryPromptPackService);
    }

    @Test
    void shouldReturnMemoryComponentType() {
        assertEquals("memory", memoryService.getComponentType());
    }

    @Test
    void shouldReturnDisabledPackWhenMemoryDisabled() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        MemoryPack pack = memoryService.buildMemoryPack(null);

        assertNotNull(pack);
        assertTrue(pack.getItems().isEmpty());
        assertEquals("", pack.getRenderedContext());
        assertEquals(Boolean.FALSE, pack.getDiagnostics().get("enabled"));
    }

    @Test
    void shouldBuildMemoryPackWithStructuredContextAndDiagnostics() {
        MemoryItem item = MemoryItem.builder()
                .id("m1")
                .type(MemoryItem.Type.PROJECT_FACT)
                .content("Project uses Spring")
                .build();
        when(memoryRetrievalService.retrieve(any())).thenReturn(List.of(
                MemoryScoredItem.builder().item(item).score(0.9).build()));
        when(memoryPromptPackService.build(any(), any())).thenReturn(MemoryPack.builder()
                .items(List.of(item))
                .diagnostics(Map.of("selectedCount", 1))
                .renderedContext("## Semantic Memory\n- [PROJECT_FACT] Project uses Spring")
                .build());

        MemoryPack pack = memoryService.buildMemoryPack(null);

        assertTrue(pack.getRenderedContext().contains("Semantic Memory"));
        assertEquals(1, pack.getItems().size());
        assertEquals(1, pack.getDiagnostics().get("selectedCount"));
        assertEquals(1, pack.getDiagnostics().get("structuredCandidates"));
        verify(memoryRetrievalService).retrieve(any(MemoryQuery.class));
    }

    @Test
    void shouldFallbackToEmptyRenderedContextWhenPromptPackIsNullRendered() {
        when(memoryRetrievalService.retrieve(any())).thenReturn(List.of());
        when(memoryPromptPackService.build(any(), any())).thenReturn(MemoryPack.builder()
                .items(List.of())
                .diagnostics(Map.of())
                .renderedContext(null)
                .build());

        MemoryPack pack = memoryService.buildMemoryPack(MemoryQuery.builder().queryText("x").build());

        assertEquals("", pack.getRenderedContext());
    }

    @Test
    void shouldReturnOnlyNonNullItemsInQueryItems() {
        MemoryItem item = MemoryItem.builder().id("m1").content("fact").build();
        when(memoryRetrievalService.retrieve(any())).thenReturn(List.of(
                MemoryScoredItem.builder().item(item).score(0.8).build(),
                MemoryScoredItem.builder().item(null).score(0.5).build()));

        List<MemoryItem> items = memoryService.queryItems(MemoryQuery.builder().queryText("fact").build());

        assertEquals(1, items.size());
        assertEquals("m1", items.get(0).getId());
    }

    @Test
    void shouldPersistTurnMemoryViaWriteService() {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .timestamp(Instant.now())
                .userText("user")
                .assistantText("assistant")
                .build();

        memoryService.persistTurnMemory(event);

        verify(memoryWriteService).persistTurnMemory(event);
    }

    @Test
    void shouldDelegateUpsertOperations() {
        MemoryItem semantic = MemoryItem.builder().id("s1").content("semantic").build();
        MemoryItem procedural = MemoryItem.builder().id("p1").content("procedural").build();

        memoryService.upsertSemanticItem(semantic);
        memoryService.upsertProceduralItem(procedural);

        verify(memoryWriteService).upsertSemanticItem(semantic);
        verify(memoryWriteService).upsertProceduralItem(procedural);
    }

    @Test
    void shouldBuildDefaultQueryWhenNullProvided() {
        when(memoryRetrievalService.retrieve(any())).thenReturn(List.of());
        when(memoryPromptPackService.build(any(), any())).thenReturn(MemoryPack.builder()
                .items(List.of())
                .diagnostics(Map.of())
                .renderedContext("")
                .build());

        MemoryPack pack = memoryService.buildMemoryPack(null);

        assertFalse(pack.getDiagnostics().isEmpty());
        verify(memoryRetrievalService).retrieve(any(MemoryQuery.class));
    }
}
