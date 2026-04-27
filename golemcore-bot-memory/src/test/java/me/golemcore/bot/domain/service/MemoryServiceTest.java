package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.memory.orchestrator.MemoryOrchestratorService;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    private MemoryOrchestratorService memoryOrchestratorService;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryOrchestratorService = mock(MemoryOrchestratorService.class);
        memoryService = new MemoryService(memoryOrchestratorService);
    }

    @Test
    void shouldReturnMemoryComponentType() {
        assertEquals("memory", memoryService.getComponentType());
    }

    @Test
    void shouldDelegateBuildMemoryPack() {
        MemoryQuery query = MemoryQuery.builder().queryText("spring").build();
        MemoryPack expected = MemoryPack.builder().items(List.of()).diagnostics(Map.of("selectedCount", 1))
                .renderedContext("memory").build();
        when(memoryOrchestratorService.buildMemoryPack(query)).thenReturn(expected);

        MemoryPack actual = memoryService.buildMemoryPack(query);

        assertEquals(expected, actual);
        verify(memoryOrchestratorService).buildMemoryPack(query);
    }

    @Test
    void shouldDelegateQueryItems() {
        MemoryQuery query = MemoryQuery.builder().queryText("fact").build();
        List<MemoryItem> expected = List.of(MemoryItem.builder().id("m1").build());
        when(memoryOrchestratorService.queryItems(query)).thenReturn(expected);

        List<MemoryItem> actual = memoryService.queryItems(query);

        assertEquals(expected, actual);
        verify(memoryOrchestratorService).queryItems(query);
    }

    @Test
    void shouldDelegatePersistTurnMemory() {
        TurnMemoryEvent event = TurnMemoryEvent.builder().timestamp(Instant.now()).build();

        memoryService.persistTurnMemory(event);

        verify(memoryOrchestratorService).persistTurnMemory(event);
    }

    @Test
    void shouldDelegateUpsertOperations() {
        MemoryItem semantic = MemoryItem.builder().id("s1").build();
        MemoryItem procedural = MemoryItem.builder().id("p1").build();

        memoryService.upsertSemanticItem(semantic);
        memoryService.upsertProceduralItem(procedural);

        verify(memoryOrchestratorService).upsertSemanticItem(semantic);
        verify(memoryOrchestratorService).upsertProceduralItem(procedural);
    }
}
