package me.golemcore.bot.domain.memory.orchestrator;

import me.golemcore.bot.domain.memory.persistence.MemoryNormalizationService;
import me.golemcore.bot.domain.memory.persistence.MemoryPersistenceOrchestrator;
import me.golemcore.bot.domain.memory.persistence.MemoryPromotionOrchestrator;
import me.golemcore.bot.domain.memory.persistence.TurnMemoryExtractionOrchestrator;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.TurnMemoryEvent;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryLifecycleOrchestratorTest {

    private RuntimeConfigService runtimeConfigService;
    private TurnMemoryExtractionOrchestrator turnMemoryExtractionOrchestrator;
    private MemoryNormalizationService memoryNormalizationService;
    private MemoryPersistenceOrchestrator memoryPersistenceOrchestrator;
    private MemoryPromotionOrchestrator memoryPromotionOrchestrator;
    private MemoryLifecycleOrchestrator memoryLifecycleOrchestrator;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        turnMemoryExtractionOrchestrator = mock(TurnMemoryExtractionOrchestrator.class);
        memoryNormalizationService = mock(MemoryNormalizationService.class);
        memoryPersistenceOrchestrator = mock(MemoryPersistenceOrchestrator.class);
        memoryPromotionOrchestrator = mock(MemoryPromotionOrchestrator.class);

        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);

        memoryLifecycleOrchestrator = new MemoryLifecycleOrchestrator(
                runtimeConfigService,
                turnMemoryExtractionOrchestrator,
                memoryNormalizationService,
                memoryPersistenceOrchestrator,
                memoryPromotionOrchestrator);
    }

    @Test
    void shouldPersistEpisodicBeforePromotion() {
        TurnMemoryEvent event = TurnMemoryEvent.builder()
                .scope("task:demo")
                .timestamp(Instant.parse("2026-03-24T12:00:00Z"))
                .userText("user")
                .assistantText("assistant")
                .build();
        MemoryItem extracted = MemoryItem.builder()
                .id("raw-1")
                .content("raw")
                .build();
        MemoryItem normalized = MemoryItem.builder()
                .id("normalized-1")
                .layer(MemoryItem.Layer.EPISODIC)
                .scope("task:demo")
                .content("normalized")
                .build();
        when(turnMemoryExtractionOrchestrator.extract(event, "task:demo")).thenReturn(List.of(extracted));
        when(memoryNormalizationService.normalizeExtractedItems(List.of(extracted), "task:demo"))
                .thenReturn(List.of(normalized));

        memoryLifecycleOrchestrator.persistTurnMemory(event);

        InOrder inOrder = inOrder(memoryPersistenceOrchestrator, memoryPromotionOrchestrator);
        inOrder.verify(memoryPersistenceOrchestrator).appendEpisodic(List.of(normalized), "task:demo");
        inOrder.verify(memoryPromotionOrchestrator).promote(List.of(normalized));
    }

    @Test
    void shouldSkipLifecycleWorkWhenMemoryDisabled() {
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(false);

        memoryLifecycleOrchestrator.persistTurnMemory(TurnMemoryEvent.builder()
                .timestamp(Instant.now())
                .userText("user")
                .assistantText("assistant")
                .build());

        verify(turnMemoryExtractionOrchestrator, never()).extract(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(memoryPersistenceOrchestrator, never()).appendEpisodic(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString());
        verify(memoryPromotionOrchestrator, never()).promote(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldNormalizeAndUpsertSemanticItem() {
        MemoryItem source = MemoryItem.builder()
                .id("sem-1")
                .scope("task:demo")
                .content("Remember this")
                .build();
        MemoryItem normalized = MemoryItem.builder()
                .id("sem-1")
                .layer(MemoryItem.Layer.SEMANTIC)
                .scope("task:demo")
                .content("Remember this")
                .build();
        when(memoryNormalizationService.normalizeForLayer(source, MemoryItem.Layer.SEMANTIC, "task:demo"))
                .thenReturn(normalized);

        memoryLifecycleOrchestrator.upsertSemanticItem(source);

        verify(memoryPersistenceOrchestrator).upsertSemantic(normalized);
    }
}
