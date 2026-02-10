package me.golemcore.bot.routing;

import me.golemcore.bot.adapter.outbound.llm.LlmAdapterFactory;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillCandidate;
import me.golemcore.bot.domain.model.SkillMatchResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridSkillMatcherTest {

    private static final String SKILL_GREETING = "greeting";
    private static final String SKILL_CODE_REVIEW = "code-review";
    private static final String DESC_HANDLE_GREETINGS = "Handle greetings";
    private static final String DESC_REVIEW_CODE = "Review code";
    private static final String TIER_BALANCED = "balanced";
    private static final String QUERY_HELLO = "hello";

    private BotProperties properties;
    private EmbeddingPort embeddingPort;
    private SkillEmbeddingStore embeddingStore;
    private LlmSkillClassifier llmClassifier;
    private LlmAdapterFactory llmAdapterFactory;
    private HybridSkillMatcher matcher;

    private final List<Skill> skills = List.of(
            Skill.builder().name(SKILL_GREETING).description(DESC_HANDLE_GREETINGS).available(true).build(),
            Skill.builder().name(SKILL_CODE_REVIEW).description(DESC_REVIEW_CODE).available(true).build());

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.getRouter().getSkillMatcher().setEnabled(true);
        properties.getRouter().getSkillMatcher().getClassifier().setEnabled(true);
        properties.getRouter().getSkillMatcher().getClassifier().setTimeoutMs(5000);
        properties.getRouter().getSkillMatcher().getCache().setEnabled(true);
        properties.getRouter().getSkillMatcher().getCache().setTtlMinutes(60);
        properties.getRouter().getSkillMatcher().getCache().setMaxSize(100);
        properties.getRouter().getSkillMatcher().setSkipClassifierThreshold(0.95);

        embeddingPort = mock(EmbeddingPort.class);
        embeddingStore = mock(SkillEmbeddingStore.class);
        llmClassifier = mock(LlmSkillClassifier.class);
        llmAdapterFactory = mock(LlmAdapterFactory.class);

        when(embeddingPort.isAvailable()).thenReturn(true);
        when(embeddingStore.isEmpty()).thenReturn(false);

        matcher = new HybridSkillMatcher(properties, embeddingPort, embeddingStore, llmClassifier, llmAdapterFactory);
    }

    // ===== Disabled / empty =====

    @Test
    void returnsNoMatchWhenDisabled() throws Exception {
        properties.getRouter().getSkillMatcher().setEnabled(false);
        matcher = new HybridSkillMatcher(properties, embeddingPort, embeddingStore, llmClassifier, llmAdapterFactory);

        SkillMatchResult result = matcher.match(QUERY_HELLO, List.of(), skills).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
        assertEquals("Skill matcher disabled", result.getReason());
    }

    @Test
    void returnsNoMatchWhenNoSkills() throws Exception {
        SkillMatchResult result = matcher.match(QUERY_HELLO, List.of(), List.of()).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
        assertEquals("No skills available", result.getReason());
    }

    @Test
    void returnsNoMatchWhenNullSkills() throws Exception {
        SkillMatchResult result = matcher.match(QUERY_HELLO, List.of(), null).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
    }

    // ===== High-score semantic skip =====

    @Test
    void skipsLlmClassifierWhenSemanticScoreAboveThreshold() throws Exception {
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        SkillCandidate.builder().name(SKILL_GREETING).description(DESC_HANDLE_GREETINGS)
                                .semanticScore(0.97)
                                .build()));

        SkillMatchResult result = matcher.match("hi there", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertEquals(SKILL_GREETING, result.getSelectedSkill());
        assertEquals(0.97, result.getConfidence(), 0.01);
        assertFalse(result.isLlmClassifierUsed());
        verifyNoInteractions(llmClassifier);
    }

    @Test
    void callsLlmClassifierWhenSemanticScoreBelowThreshold() throws Exception {
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        List<SkillCandidate> candidates = List.of(
                SkillCandidate.builder().name(SKILL_GREETING).description(DESC_HANDLE_GREETINGS).semanticScore(0.80)
                        .build(),
                SkillCandidate.builder().name(SKILL_CODE_REVIEW).description(DESC_REVIEW_CODE).semanticScore(0.70)
                        .build());
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble())).thenReturn(candidates);

        LlmPort classifierLlm = mock(LlmPort.class);
        when(llmAdapterFactory.getActiveAdapter()).thenReturn(classifierLlm);
        when(llmClassifier.classify(anyString(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new LlmSkillClassifier.ClassificationResult(SKILL_GREETING, 0.9, TIER_BALANCED,
                                "Greeting detected")));

        SkillMatchResult result = matcher.match("hello world", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertEquals(SKILL_GREETING, result.getSelectedSkill());
        assertEquals(0.9, result.getConfidence(), 0.01);
        assertEquals(TIER_BALANCED, result.getModelTier());
        assertTrue(result.isLlmClassifierUsed());
    }

    // ===== LLM classifier fallback =====

    @Test
    void fallsBackToSemanticWhenLlmClassifierFails() throws Exception {
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        List<SkillCandidate> candidates = List.of(
                SkillCandidate.builder().name(SKILL_CODE_REVIEW).description(DESC_REVIEW_CODE).semanticScore(0.85)
                        .build());
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble())).thenReturn(candidates);

        LlmPort classifierLlm = mock(LlmPort.class);
        when(llmAdapterFactory.getActiveAdapter()).thenReturn(classifierLlm);
        when(llmClassifier.classify(anyString(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM unavailable")));

        SkillMatchResult result = matcher.match("review my PR", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertEquals(SKILL_CODE_REVIEW, result.getSelectedSkill());
        assertEquals(0.85, result.getConfidence(), 0.01);
        assertFalse(result.isLlmClassifierUsed());
        assertTrue(result.getReason().contains("semantic fallback"));
    }

    // ===== Empty semantic candidates =====

    @Test
    void runsLlmClassifierForTierDetectionWhenNoCandidates() throws Exception {
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        LlmPort classifierLlm = mock(LlmPort.class);
        when(llmAdapterFactory.getActiveAdapter()).thenReturn(classifierLlm);
        when(llmClassifier.classify(anyString(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new LlmSkillClassifier.ClassificationResult(null, 0.5, "default", "No skill needed")));

        SkillMatchResult result = matcher.match("what time is it?", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
        assertEquals("default", result.getModelTier());
    }

    @Test
    void returnsDefaultTierWhenNoCandidatesAndClassifierDisabled() throws Exception {
        properties.getRouter().getSkillMatcher().getClassifier().setEnabled(false);
        matcher = new HybridSkillMatcher(properties, embeddingPort, embeddingStore, llmClassifier, llmAdapterFactory);

        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        SkillMatchResult result = matcher.match("something", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
        assertEquals(TIER_BALANCED, result.getModelTier());
    }

    // ===== Re-indexing =====

    @Test
    void reIndexesWhenStoreEmpty() throws Exception {
        when(embeddingStore.isEmpty()).thenReturn(true);
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        SkillCandidate.builder().name(SKILL_GREETING).description("Greet").semanticScore(0.99)
                                .build()));

        matcher.match("hi", List.of(), skills).get(5, TimeUnit.SECONDS);

        verify(embeddingStore).indexSkills(anyList());
    }

    // ===== Error handling =====

    @Test
    void returnsNoMatchOnSemanticSearchFailure() throws Exception {
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Embedding service down")));

        // semantic search fails → empty candidates → classifier runs for tier
        properties.getRouter().getSkillMatcher().getClassifier().setEnabled(false);
        matcher = new HybridSkillMatcher(properties, embeddingPort, embeddingStore, llmClassifier, llmAdapterFactory);

        SkillMatchResult result = matcher.match(QUERY_HELLO, List.of(), skills).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
        assertEquals(TIER_BALANCED, result.getModelTier());
    }

    // ===== Index/ready/enabled =====

    @Test
    void indexSkillsFiltersUnavailable() {
        List<Skill> mixed = List.of(
                Skill.builder().name("ok").description("available").available(true).build(),
                Skill.builder().name("bad").description("not available").available(false).build());

        matcher.indexSkills(mixed);

        verify(embeddingStore).indexSkills(argThat(list -> list.size() == 1 && "ok".equals(list.get(0).getName())));
    }

    @Test
    void indexSkillsSkipsWhenEmbeddingUnavailable() {
        when(embeddingPort.isAvailable()).thenReturn(false);

        matcher.indexSkills(skills);

        verifyNoInteractions(embeddingStore);
    }

    @Test
    void isReadyWhenIndexedAndStoreNotEmpty() {
        when(embeddingStore.isEmpty()).thenReturn(false);
        matcher.indexSkills(skills);

        assertTrue(matcher.isReady());
    }

    @Test
    void clearIndexResetsState() {
        matcher.indexSkills(skills);
        matcher.clearIndex();

        assertFalse(matcher.isReady());
        verify(embeddingStore).clear();
    }

    // ===== InterruptedException handling =====

    @Test
    void fallsBackToSemanticOnInterruptedExceptionInClassifier() throws Exception {
        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        List<SkillCandidate> candidates = List.of(
                SkillCandidate.builder().name(SKILL_CODE_REVIEW).description(DESC_REVIEW_CODE).semanticScore(0.85)
                        .build());
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble())).thenReturn(candidates);

        LlmPort classifierLlm = mock(LlmPort.class);
        when(llmAdapterFactory.getActiveAdapter()).thenReturn(classifierLlm);

        // Simulate InterruptedException via ExecutionException wrapping
        CompletableFuture<LlmSkillClassifier.ClassificationResult> interruptedFuture = new CompletableFuture<>();
        interruptedFuture.completeExceptionally(new RuntimeException("Interrupted"));
        when(llmClassifier.classify(anyString(), anyList(), anyList(), any()))
                .thenReturn(interruptedFuture);

        SkillMatchResult result = matcher.match("review my PR", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertEquals(SKILL_CODE_REVIEW, result.getSelectedSkill());
        assertFalse(result.isLlmClassifierUsed());
        assertTrue(result.getReason().contains("semantic fallback"));
    }

    @Test
    void returnsEmptyListOnInterruptedExceptionInSemanticSearch() throws Exception {
        // Make embed() throw an ExecutionException
        CompletableFuture<float[]> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Embedding interrupted"));
        when(embeddingPort.embed(anyString())).thenReturn(failedFuture);

        properties.getRouter().getSkillMatcher().getClassifier().setEnabled(false);
        matcher = new HybridSkillMatcher(properties, embeddingPort, embeddingStore, llmClassifier, llmAdapterFactory);

        SkillMatchResult result = matcher.match(QUERY_HELLO, List.of(), skills).get(5, TimeUnit.SECONDS);

        assertNull(result.getSelectedSkill());
        assertEquals(TIER_BALANCED, result.getModelTier());
    }

    // ===== Classifier disabled → semantic only =====

    @Test
    void usesSemanticResultWhenClassifierDisabled() throws Exception {
        properties.getRouter().getSkillMatcher().getClassifier().setEnabled(false);
        matcher = new HybridSkillMatcher(properties, embeddingPort, embeddingStore, llmClassifier, llmAdapterFactory);

        when(embeddingPort.embed(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new float[] { 1.0f }));
        when(embeddingStore.findSimilar(any(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        SkillCandidate.builder().name(SKILL_GREETING).description("Greet").semanticScore(0.80)
                                .build()));

        SkillMatchResult result = matcher.match("hi", List.of(), skills).get(5, TimeUnit.SECONDS);

        assertEquals(SKILL_GREETING, result.getSelectedSkill());
        assertFalse(result.isLlmClassifierUsed());
        verifyNoInteractions(llmClassifier);
    }
}
