package me.golemcore.bot.domain.selfevolving.tactic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TacticEmbeddingBackfillSchedulerTest {

    private TacticEmbeddingIndexService tacticEmbeddingIndexService;
    private TacticIndexRebuildService tacticIndexRebuildService;
    private LocalEmbeddingBootstrapService localEmbeddingBootstrapService;
    private TacticEmbeddingBackfillScheduler scheduler;

    @BeforeEach
    void setUp() {
        tacticEmbeddingIndexService = mock(TacticEmbeddingIndexService.class);
        tacticIndexRebuildService = mock(TacticIndexRebuildService.class);
        localEmbeddingBootstrapService = mock(LocalEmbeddingBootstrapService.class);
        scheduler = new TacticEmbeddingBackfillScheduler(
                tacticEmbeddingIndexService,
                tacticIndexRebuildService,
                localEmbeddingBootstrapService,
                Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void shouldSkipBackfillWhenNoTacticsAreMissingPersistedEntries() {
        when(tacticEmbeddingIndexService.findMissingPersistedEntryTacticIds()).thenReturn(List.of());

        scheduler.tick();

        verify(tacticIndexRebuildService, never()).rebuildAll();
        verify(localEmbeddingBootstrapService, never()).probeStatus();
    }

    @Test
    void shouldSkipBackfillWhenLocalEmbeddingRuntimeIsUnavailable() {
        when(tacticEmbeddingIndexService.findMissingPersistedEntryTacticIds()).thenReturn(List.of("planner"));
        when(localEmbeddingBootstrapService.probeStatus()).thenReturn(TacticSearchStatus.builder()
                .mode("bm25")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeHealthy(false)
                .modelAvailable(false)
                .degraded(true)
                .updatedAt(Instant.parse("2026-04-05T14:59:00Z"))
                .build());

        scheduler.tick();

        verify(tacticIndexRebuildService, never()).rebuildAll();
    }

    @Test
    void shouldRebuildWhenMissingPersistedEntriesExistAndLocalEmbeddingRuntimeIsAvailable() {
        when(tacticEmbeddingIndexService.findMissingPersistedEntryTacticIds()).thenReturn(List.of("planner"));
        when(localEmbeddingBootstrapService.probeStatus()).thenReturn(TacticSearchStatus.builder()
                .mode("hybrid")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeHealthy(true)
                .modelAvailable(true)
                .degraded(false)
                .updatedAt(Instant.parse("2026-04-05T14:59:00Z"))
                .build());

        scheduler.tick();

        verify(tacticIndexRebuildService).rebuildAll();
    }
}
