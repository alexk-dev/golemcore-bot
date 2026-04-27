package me.golemcore.bot.domain.memory.orchestrator;

import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryOrchestratorServiceTest {

    private MemoryContextOrchestrator memoryContextOrchestrator;
    private MemoryLifecycleOrchestrator memoryLifecycleOrchestrator;
    private MemoryOrchestratorService memoryOrchestratorService;

    @BeforeEach
    void setUp() {
        memoryContextOrchestrator = mock(MemoryContextOrchestrator.class);
        memoryLifecycleOrchestrator = mock(MemoryLifecycleOrchestrator.class);
        memoryOrchestratorService = new MemoryOrchestratorService(memoryContextOrchestrator,
                memoryLifecycleOrchestrator);
    }

    @Test
    void shouldDelegatePromptAssemblyAndQueriesToContextOrchestrator() {
        MemoryQuery query = MemoryQuery.builder().queryText("spring").build();
        MemoryPack pack = MemoryPack.builder().renderedContext("ctx").build();
        MemoryItem item = MemoryItem.builder().id("m1").content("fact").build();
        when(memoryContextOrchestrator.buildMemoryPack(query)).thenReturn(pack);
        when(memoryContextOrchestrator.queryItems(query)).thenReturn(List.of(item));

        assertSame(pack, memoryOrchestratorService.buildMemoryPack(query));
        assertEquals(List.of(item), memoryOrchestratorService.queryItems(query));
    }

    @Test
    void shouldDelegateLifecycleMethods() {
        MemoryItem semantic = MemoryItem.builder().id("s1").build();
        MemoryItem procedural = MemoryItem.builder().id("p1").build();
        TurnMemoryEvent event = TurnMemoryEvent.builder().timestamp(Instant.parse("2026-03-24T12:00:00Z")).userText("u")
                .assistantText("a").build();

        memoryOrchestratorService.persistTurnMemory(event);
        memoryOrchestratorService.upsertSemanticItem(semantic);
        memoryOrchestratorService.upsertProceduralItem(procedural);

        verify(memoryLifecycleOrchestrator).persistTurnMemory(event);
        verify(memoryLifecycleOrchestrator).upsertSemanticItem(semantic);
        verify(memoryLifecycleOrchestrator).upsertProceduralItem(procedural);
    }
}
