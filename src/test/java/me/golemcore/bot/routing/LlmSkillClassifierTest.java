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
                        .name("code-review")
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

        assertEquals("code-review", result.skill());
        assertEquals(0.95, result.confidence());
        assertEquals("balanced", result.modelTier());
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
        assertEquals("fast", result.modelTier());
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
                .classify("Test message", List.of(), candidates, mockLlmPort)
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
                .classify("Test message", List.of(), candidates, mockLlmPort)
                .join();

        // Should fall back to top semantic candidate
        assertEquals("code-review", result.skill());
        assertEquals(0.89, result.confidence());
        assertEquals("balanced", result.modelTier());
        assertTrue(result.reason().contains("Parse failed"));
    }

    @Test
    void classify_fallsBackOnLlmException() throws Exception {
        when(mockLlmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM error")));

        LlmSkillClassifier.ClassificationResult result = classifier
                .classify("Test message", List.of(), candidates, mockLlmPort)
                .join();

        // Should fall back to top semantic candidate
        assertEquals("code-review", result.skill());
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

        assertEquals("code-review", result.skill());
        assertEquals(0.8, result.confidence()); // default
        assertEquals("balanced", result.modelTier()); // default
        assertEquals("LLM classification", result.reason()); // default
    }
}
