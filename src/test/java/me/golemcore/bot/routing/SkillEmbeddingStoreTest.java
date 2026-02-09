package me.golemcore.bot.routing;

import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillCandidate;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillEmbeddingStoreTest {

    private EmbeddingPort embeddingPort;
    private SkillEmbeddingStore store;

    @BeforeEach
    void setUp() {
        embeddingPort = mock(EmbeddingPort.class);
        store = new SkillEmbeddingStore(embeddingPort);
    }

    // ===== indexSkill =====

    @Test
    void shouldIndexSingleSkill() {
        Skill skill = Skill.builder().name("greeting").description("Handle greetings").build();
        when(embeddingPort.embed("greeting: Handle greetings"))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f, 0.0f }));

        store.indexSkill(skill);

        assertEquals(1, store.size());
        assertFalse(store.isEmpty());
    }

    @Test
    void shouldHandleEmbeddingFailureGracefully() {
        Skill skill = Skill.builder().name("broken").description("Fails").build();
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API error")));

        assertDoesNotThrow(() -> store.indexSkill(skill));
        assertTrue(store.isEmpty());
    }

    // ===== indexSkills (batch) =====

    @Test
    void shouldIndexMultipleSkillsInBatch() {
        List<Skill> skills = List.of(
                Skill.builder().name("greeting").description("Handle greetings").build(),
                Skill.builder().name("code-review").description("Review code").build());

        when(embeddingPort.embedBatch(anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new float[] { 1.0f, 0.0f },
                        new float[] { 0.0f, 1.0f })));

        store.indexSkills(skills);

        assertEquals(2, store.size());
    }

    @Test
    void shouldSkipEmptySkillList() {
        store.indexSkills(List.of());

        assertEquals(0, store.size());
        verifyNoInteractions(embeddingPort);
    }

    @Test
    void shouldFallbackToIndividualIndexingOnBatchFailure() {
        List<Skill> skills = List.of(
                Skill.builder().name("greeting").description("Handle greetings").build(),
                Skill.builder().name("code-review").description("Review code").build());

        when(embeddingPort.embedBatch(anyList()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Batch failed")));

        when(embeddingPort.embed("greeting: Handle greetings"))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f, 0.0f }));
        when(embeddingPort.embed("code-review: Review code"))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 0.0f, 1.0f }));

        store.indexSkills(skills);

        assertEquals(2, store.size());
        verify(embeddingPort, times(2)).embed(anyString());
    }

    // ===== findSimilar =====

    @Test
    void shouldReturnCandidatesAboveMinScore() {
        indexTwoSkills();

        // Query embedding closer to greeting
        float[] queryEmbedding = new float[] { 0.9f, 0.1f };
        when(embeddingPort.cosineSimilarity(queryEmbedding, new float[] { 1.0f, 0.0f })).thenReturn(0.95);
        when(embeddingPort.cosineSimilarity(queryEmbedding, new float[] { 0.0f, 1.0f })).thenReturn(0.20);

        List<SkillCandidate> candidates = store.findSimilar(queryEmbedding, 5, 0.5);

        assertEquals(1, candidates.size());
        assertEquals("greeting", candidates.get(0).getName());
        assertEquals(0.95, candidates.get(0).getSemanticScore(), 0.001);
    }

    @Test
    void shouldReturnEmptyWhenNoCandidatesAboveMinScore() {
        indexTwoSkills();

        float[] queryEmbedding = new float[] { 0.5f, 0.5f };
        when(embeddingPort.cosineSimilarity(any(), any())).thenReturn(0.1);

        List<SkillCandidate> candidates = store.findSimilar(queryEmbedding, 5, 0.5);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldLimitResultsToTopK() {
        indexTwoSkills();

        float[] queryEmbedding = new float[] { 0.5f, 0.5f };
        when(embeddingPort.cosineSimilarity(queryEmbedding, new float[] { 1.0f, 0.0f })).thenReturn(0.8);
        when(embeddingPort.cosineSimilarity(queryEmbedding, new float[] { 0.0f, 1.0f })).thenReturn(0.9);

        List<SkillCandidate> candidates = store.findSimilar(queryEmbedding, 1, 0.5);

        assertEquals(1, candidates.size());
        assertEquals("code-review", candidates.get(0).getName());
    }

    @Test
    void shouldSortCandidatesByScoreDescending() {
        indexTwoSkills();

        float[] queryEmbedding = new float[] { 0.5f, 0.5f };
        when(embeddingPort.cosineSimilarity(queryEmbedding, new float[] { 1.0f, 0.0f })).thenReturn(0.7);
        when(embeddingPort.cosineSimilarity(queryEmbedding, new float[] { 0.0f, 1.0f })).thenReturn(0.9);

        List<SkillCandidate> candidates = store.findSimilar(queryEmbedding, 10, 0.5);

        assertEquals(2, candidates.size());
        assertEquals("code-review", candidates.get(0).getName());
        assertEquals("greeting", candidates.get(1).getName());
    }

    @Test
    void shouldReturnEmptyWhenStoreIsEmpty() {
        float[] queryEmbedding = new float[] { 1.0f, 0.0f };

        List<SkillCandidate> candidates = store.findSimilar(queryEmbedding, 5, 0.5);

        assertTrue(candidates.isEmpty());
    }

    // ===== clear =====

    @Test
    void shouldClearAllEmbeddings() {
        indexTwoSkills();
        assertEquals(2, store.size());

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.isEmpty());
    }

    // ===== isEmpty / size =====

    @Test
    void shouldBeEmptyInitially() {
        assertTrue(store.isEmpty());
        assertEquals(0, store.size());
    }

    // ===== Helper =====

    private void indexTwoSkills() {
        List<Skill> skills = List.of(
                Skill.builder().name("greeting").description("Handle greetings").build(),
                Skill.builder().name("code-review").description("Review code").build());

        when(embeddingPort.embedBatch(anyList()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        new float[] { 1.0f, 0.0f },
                        new float[] { 0.0f, 1.0f })));

        store.indexSkills(skills);
    }
}
