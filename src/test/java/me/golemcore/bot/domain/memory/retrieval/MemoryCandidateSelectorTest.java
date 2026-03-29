package me.golemcore.bot.domain.memory.retrieval;

import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryCandidateSelectorTest {

    private final MemoryCandidateSelector memoryCandidateSelector = new MemoryCandidateSelector();

    @Test
    void shouldPreferEarlierScopesBeforeGlobalFallbackWhenApplyingLayerCaps() {
        MemoryRetrievalPlan plan = MemoryRetrievalPlan.builder()
                .query(MemoryQuery.builder()
                        .workingTopK(0)
                        .episodicTopK(1)
                        .semanticTopK(1)
                        .proceduralTopK(0)
                        .build())
                .requestedScope("task:build-cache")
                .requestedScopes(List.of("task:build-cache", "global"))
                .build();

        List<MemoryScoredItem> selected = memoryCandidateSelector.select(plan, List.of(
                scored("global-sem", "global", MemoryItem.Layer.SEMANTIC, "g-sem", 0.99),
                scored("task-sem", "task:build-cache", MemoryItem.Layer.SEMANTIC, "t-sem", 0.90),
                scored("task-ep", "task:build-cache", MemoryItem.Layer.EPISODIC, "t-ep", 0.80),
                scored("global-ep", "global", MemoryItem.Layer.EPISODIC, "g-ep", 0.70)));

        assertEquals(List.of("task-sem", "task-ep"),
                selected.stream().map(item -> item.getItem().getId()).toList());
    }

    @Test
    void shouldDeduplicateSelectedCandidatesByFingerprintBeforeReturning() {
        MemoryRetrievalPlan plan = MemoryRetrievalPlan.builder()
                .query(MemoryQuery.builder()
                        .workingTopK(0)
                        .episodicTopK(0)
                        .semanticTopK(3)
                        .proceduralTopK(0)
                        .build())
                .requestedScope("global")
                .requestedScopes(List.of("global"))
                .build();

        List<MemoryScoredItem> selected = memoryCandidateSelector.select(plan, List.of(
                scored("sem-1", "global", MemoryItem.Layer.SEMANTIC, "dup", 0.95),
                scored("sem-2", "global", MemoryItem.Layer.SEMANTIC, "dup", 0.94),
                scored("sem-3", "global", MemoryItem.Layer.SEMANTIC, "unique", 0.93)));

        assertEquals(List.of("sem-1", "sem-3"),
                selected.stream().map(item -> item.getItem().getId()).toList());
    }

    private MemoryScoredItem scored(String id, String scope, MemoryItem.Layer layer, String fingerprint, double score) {
        return MemoryScoredItem.builder()
                .score(score)
                .item(MemoryItem.builder()
                        .id(id)
                        .scope(scope)
                        .layer(layer)
                        .type(MemoryItem.Type.PROJECT_FACT)
                        .content(id)
                        .fingerprint(fingerprint)
                        .build())
                .build();
    }
}
