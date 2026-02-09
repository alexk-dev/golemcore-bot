package me.golemcore.bot.routing;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.SkillCandidate;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmSkillClassifierTest {

    private static final String SKILL_CODE_REVIEW = "code-review";
    private static final String TIER_BALANCED = "balanced";
    private static final String TIER_FAST = "fast";
    private static final String TEST_MESSAGE = "Test message";

    private LlmSkillClassifier classifier;
    private BotProperties properties;
    private LlmPort mockLlmPort;
    private List<SkillCandidate> candidates;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        properties.getRouter().getSkillMatcher().getClassifier().setTimeoutMs(5000);
        properties.getRouter().getSkillMatcher().getClassifier().setModel("test-model");

        classifier = new LlmSkillClassifier(properties, new ObjectMapper());
        mockLlmPort = mock(LlmPort.class);

        candidates = List.of(
                SkillCandidate.builder()
                        .name(SKILL_CODE_REVIEW)
                        .description("Review code")
                        .semanticScore(0.89)
                        .build(),
                SkillCandidate.builder()
                        .name("refactor")
                        .description("Refactor code")
                        .semanticScore(0.87)
                        .build());
    }

    @Test
    void classify_parsesValidJsonResponse() throws Exception {
        String jsonResponse = """
                {"skill": "code-review", "confidence": 0.95, "model_tier": "balanced", "reason": "User asked for review"}
                """;

        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(jsonResponse).build()));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify("Review my code", List.of(), candidates, mockLlmPort)
                .join();

        assertEquals(SKILL_CODE_REVIEW, result.skill());
        assertEquals(0.95, result.confidence());
        assertEquals(TIER_BALANCED, result.modelTier());
        assertEquals("User asked for review", result.reason());
    }

    @Test
    void classify_parsesJsonInMarkdownBlock() throws Exception {
        String jsonResponse = """
                Here's my analysis:
                ```json
                {"skill": "refactor", "confidence": 0.88, "model_tier": "smart", "reason": "Complex task"}
                ```
                """;

        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(jsonResponse).build()));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify("Refactor this code", List.of(), candidates, mockLlmPort)
                .join();

        assertEquals("refactor", result.skill());
        assertEquals(0.88, result.confidence());
        assertEquals("smart", result.modelTier());
    }

    @Test
    void classify_handlesNoneSkill() throws Exception {
        String jsonResponse = """
                {"skill": "none", "confidence": 0.90, "model_tier": "fast", "reason": "No matching skill"}
                """;

        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(jsonResponse).build()));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify("What's the weather?", List.of(), candidates, mockLlmPort)
                .join();

        assertNull(result.skill());
        assertEquals(0.90, result.confidence());
        assertEquals(TIER_FAST, result.modelTier());
    }

    @Test
    void classify_rejectsUnknownSkill() throws Exception {
        String jsonResponse = """
                {"skill": "unknown-skill", "confidence": 0.85, "model_tier": "balanced", "reason": "Test"}
                """;

        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(jsonResponse).build()));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify(TEST_MESSAGE, List.of(), candidates, mockLlmPort)
                .join();

        // Should be null because skill doesn't exist in candidates
        assertNull(result.skill());
    }

    @Test
    void classify_fallsBackOnInvalidJson() throws Exception {
        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content("This is not JSON at all").build()));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify(TEST_MESSAGE, List.of(), candidates, mockLlmPort)
                .join();

        // Should fall back to top semantic candidate
        assertEquals(SKILL_CODE_REVIEW, result.skill());
        assertEquals(0.89, result.confidence());
        assertEquals(TIER_BALANCED, result.modelTier());
        assertTrue(result.reason().contains("Parse failed"));
    }

    @Test
    void classify_fallsBackOnLlmException() throws Exception {
        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM error")));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify(TEST_MESSAGE, List.of(), candidates, mockLlmPort)
                .join();

        // Should fall back to top semantic candidate
        assertEquals(SKILL_CODE_REVIEW, result.skill());
        assertEquals(0.89, result.confidence());
    }

    @Test
    void classify_usesDefaultsForMissingFields() throws Exception {
        String jsonResponse = """
                {"skill": "code-review"}
                """;

        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(jsonResponse).build()));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify("Review code", List.of(), candidates, mockLlmPort)
                .join();

        assertEquals(SKILL_CODE_REVIEW, result.skill());
        assertEquals(0.8, result.confidence()); // default
        assertEquals(TIER_BALANCED, result.modelTier()); // default
        assertEquals("LLM classification", result.reason()); // default
    }

    // ===== Exception handling =====

    @Test
    void classify_fallsBackOnTimeoutException() throws Exception {
        CompletableFuture<LlmResponse> timeoutFuture = new CompletableFuture<>();
        timeoutFuture.completeExceptionally(new java.util.concurrent.TimeoutException("Request timed out"));
        when(mockLlmPort.chat(any(LlmRequest.class))).thenReturn(timeoutFuture);

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify(TEST_MESSAGE, List.of(), candidates, mockLlmPort)
                .join();

        // Should fallback to top semantic candidate
        assertEquals(SKILL_CODE_REVIEW, result.skill());
        assertEquals(0.89, result.confidence());
        assertEquals(TIER_BALANCED, result.modelTier());
    }

    @Test
    void classify_returnsNoMatchWhenEmptyCandidatesAndLlmFails() throws Exception {
        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM error")));

        List<SkillCandidate> emptyCandidates = List.of();
        LlmSkillClassifier.ClassificationResult result = classifier
                .classify(TEST_MESSAGE, List.of(), emptyCandidates, mockLlmPort)
                .join();

        assertNull(result.skill());
        assertEquals(0, result.confidence());
        assertEquals(TIER_FAST, result.modelTier());
    }

    @Test
    void classify_returnsNoMatchOnInvalidJsonWithEmptyCandidates() throws Exception {
        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content("not json").build()));

        List<SkillCandidate> emptyCandidates = List.of();
        LlmSkillClassifier.ClassificationResult result = classifier
                .classify(TEST_MESSAGE, List.of(), emptyCandidates, mockLlmPort)
                .join();

        assertNull(result.skill());
        assertEquals(TIER_FAST, result.modelTier());
        assertTrue(result.reason().contains("Parse failed"));
    }

    @Test
    void classify_includesConversationHistoryInPrompt() throws Exception {
        String jsonResponse = """
                {"skill": "code-review", "confidence": 0.9, "model_tier": "balanced", "reason": "Context"}
                """;
        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        LlmResponse.builder().content(jsonResponse).build()));

        List<me.golemcore.bot.domain.model.Message> history = List.of(
                me.golemcore.bot.domain.model.Message.builder()
                        .role("user").content("Previous question").build(),
                me.golemcore.bot.domain.model.Message.builder()
                        .role("assistant").content("Previous answer").build());

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify("Follow up question", history, candidates, mockLlmPort)
                .join();

        assertEquals(SKILL_CODE_REVIEW, result.skill());
    }
}
