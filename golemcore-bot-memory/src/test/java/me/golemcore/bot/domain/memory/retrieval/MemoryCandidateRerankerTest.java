package me.golemcore.bot.domain.memory.retrieval;

import me.golemcore.bot.domain.memory.model.MemoryRetrievalPlan;
import me.golemcore.bot.domain.model.MemoryItem;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.MemoryScoredItem;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryCandidateRerankerTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryCandidateReranker memoryCandidateReranker;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        memoryCandidateReranker = new MemoryCandidateReranker(runtimeConfigService);
        when(runtimeConfigService.isMemoryRerankingEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemoryRerankingProfile()).thenReturn("balanced");
    }

    @Test
    void shouldPreferExactTitleAndContentMatchesDuringSecondPass() {
        MemoryRetrievalPlan plan = MemoryRetrievalPlan.builder()
                .query(MemoryQuery.builder().queryText("redis connection reset").activeSkill("ops").build())
                .requestedScope("global").requestedScopes(List.of("global")).episodicLookbackDays(1).build();

        MemoryScoredItem broadMatch = MemoryScoredItem.builder()
                .item(item("broad-match", "incident note", "redis connection issue")).score(0.96).build();
        MemoryScoredItem exactMatch = MemoryScoredItem.builder().item(
                item("exact-match", "redis connection reset playbook", "redis connection reset recovery checklist"))
                .score(0.74).build();

        List<MemoryScoredItem> reranked = memoryCandidateReranker.rerank(plan, List.of(broadMatch, exactMatch));

        assertEquals(List.of("exact-match", "broad-match"),
                reranked.stream().map(candidate -> candidate.getItem().getId()).toList());
    }

    @Test
    void shouldKeepOriginalOrderWhenRerankingDisabled() {
        when(runtimeConfigService.isMemoryRerankingEnabled()).thenReturn(false);

        MemoryRetrievalPlan plan = MemoryRetrievalPlan.builder()
                .query(MemoryQuery.builder().queryText("redis connection reset").build()).requestedScope("global")
                .requestedScopes(List.of("global")).episodicLookbackDays(1).build();

        MemoryScoredItem first = MemoryScoredItem.builder().item(item("first", "first note", "plain content"))
                .score(0.90).build();
        MemoryScoredItem second = MemoryScoredItem.builder()
                .item(item("second", "redis connection reset playbook", "exact phrase")).score(0.70).build();

        List<MemoryScoredItem> reranked = memoryCandidateReranker.rerank(plan, List.of(first, second));

        assertEquals(List.of("first", "second"),
                reranked.stream().map(candidate -> candidate.getItem().getId()).toList());
    }

    private MemoryItem item(String id, String title, String content) {
        return MemoryItem.builder().id(id).layer(MemoryItem.Layer.PROCEDURAL).type(MemoryItem.Type.FIX).title(title)
                .content(content).confidence(0.80).salience(0.80).createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }
}
