package me.golemcore.bot.domain.memory.orchestrator;

import me.golemcore.bot.domain.memory.diagnostics.MemoryDiagnosticsAssembler;
import me.golemcore.bot.domain.memory.model.MemoryAssemblyResult;
import me.golemcore.bot.domain.memory.model.MemoryContextRequest;
import me.golemcore.bot.domain.memory.model.MemoryPackSection;
import me.golemcore.bot.domain.memory.model.MemoryPackSectionType;
import me.golemcore.bot.domain.memory.retrieval.MemoryQueryFactory;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.memory.MemoryPromptPackService;
import me.golemcore.bot.domain.memory.MemoryRetrievalService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryContextOrchestratorTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryQueryFactory memoryQueryFactory;
    private MemoryRetrievalService memoryRetrievalService;
    private MemoryPromptPackService memoryPromptPackService;
    private MemoryDiagnosticsAssembler memoryDiagnosticsAssembler;
    private MemoryContextOrchestrator memoryContextOrchestrator;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryQueryFactory = mock(MemoryQueryFactory.class);
        memoryRetrievalService = mock(MemoryRetrievalService.class);
        memoryPromptPackService = mock(MemoryPromptPackService.class);
        memoryDiagnosticsAssembler = mock(MemoryDiagnosticsAssembler.class);

        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);

        memoryContextOrchestrator = new MemoryContextOrchestrator(runtimeConfigService, memoryQueryFactory,
                memoryRetrievalService, memoryPromptPackService, memoryDiagnosticsAssembler);
    }

    @Test
    void shouldReturnDisabledPackWithoutCallingDownstreamCollaborators() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        MemoryPack pack = memoryContextOrchestrator.buildMemoryPack(null);

        assertTrue(pack.getItems().isEmpty());
        assertEquals("", pack.getRenderedContext());
        assertEquals(Boolean.FALSE, pack.getDiagnostics().get("enabled"));
        verify(memoryQueryFactory, never()).createForPrompt(any());
        verify(memoryRetrievalService, never()).retrieve(any());
    }

    @Test
    void shouldAssemblePromptMemoryThroughQueryFactoryPromptPackAndDiagnosticsAssembler() {
        MemoryQuery incoming = MemoryQuery.builder().queryText("spring").build();
        MemoryQuery normalized = MemoryQuery.builder().queryText("spring").scope("task:build-cache")
                .scopeChain(List.of("task:build-cache", "global")).softPromptBudgetTokens(1200)
                .maxPromptBudgetTokens(1800).build();
        MemoryItem item = MemoryItem.builder().id("sem-1").layer(MemoryItem.Layer.SEMANTIC)
                .type(MemoryItem.Type.PROJECT_FACT).content("Project uses Spring").build();
        List<MemoryScoredItem> scoredItems = List.of(MemoryScoredItem.builder().item(item).score(0.91).build());
        MemoryPack promptPack = MemoryPack.builder().items(List.of(item))
                .sections(List.of(MemoryPackSection.builder().type(MemoryPackSectionType.SEMANTIC_MEMORY)
                        .title("Relevant Facts").lines(List.of("[PROJECT_FACT] Project uses Spring")).build()))
                .disclosureMode("summary").diagnostics(Map.of("selectedCount", 1))
                .renderedContext("## Relevant Facts\n- [PROJECT_FACT] Project uses Spring").build();
        Map<String, Object> diagnostics = Map.of("enabled", true, "structuredCandidates", 1, "selectedCount", 1);

        when(memoryQueryFactory.createForPrompt(any())).thenReturn(normalized);
        when(memoryRetrievalService.retrieve(normalized)).thenReturn(scoredItems);
        when(memoryPromptPackService.build(normalized, scoredItems)).thenReturn(promptPack);
        when(memoryDiagnosticsAssembler.buildPromptDiagnostics(normalized, scoredItems, promptPack))
                .thenReturn(diagnostics);

        MemoryAssemblyResult result = memoryContextOrchestrator
                .assemblePromptMemory(MemoryContextRequest.builder().query(incoming).build());

        assertSame(normalized, result.getQuery());
        assertEquals(scoredItems, result.getScoredItems());
        assertEquals(diagnostics, result.getDiagnostics());
        assertEquals(diagnostics, result.getMemoryPack().getDiagnostics());
        assertEquals("summary", result.getMemoryPack().getDisclosureMode());
        assertEquals("## Relevant Facts\n- [PROJECT_FACT] Project uses Spring",
                result.getMemoryPack().getRenderedContext());

        MemoryPack pack = memoryContextOrchestrator.buildMemoryPack(incoming);
        assertEquals("## Relevant Facts\n- [PROJECT_FACT] Project uses Spring", pack.getRenderedContext());
        assertEquals(diagnostics, pack.getDiagnostics());
        verify(memoryQueryFactory, times(2))
                .createForPrompt(argThat(request -> request != null && incoming.equals(request.getQuery())));
    }

    @Test
    void shouldNormalizeToolQueryBeforeReturningNonNullItems() {
        MemoryQuery incoming = MemoryQuery.builder().queryText("redis").build();
        MemoryQuery normalized = MemoryQuery.builder().queryText("redis").scope("global").build();
        MemoryItem item = MemoryItem.builder().id("m1").content("fact").build();
        when(memoryQueryFactory.createForTool(incoming)).thenReturn(normalized);
        when(memoryRetrievalService.retrieve(normalized))
                .thenReturn(List.of(MemoryScoredItem.builder().item(item).score(0.88).build(),
                        MemoryScoredItem.builder().item(null).score(0.10).build()));

        List<MemoryItem> items = memoryContextOrchestrator.queryItems(incoming);

        assertEquals(List.of(item), items);
        verify(memoryQueryFactory).createForTool(incoming);
    }
}
