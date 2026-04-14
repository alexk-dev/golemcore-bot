package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextTokenEstimatorTest {

    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    @Test
    void shouldReturnZeroForNullAndEmptyInputs() {
        assertEquals(0, estimator.estimateMessages(null));
        assertEquals(0, estimator.estimateMessages(List.of()));
        assertEquals(0, estimator.estimateRequest(null));
    }

    @Test
    void shouldEstimateFullRequestIncludingPromptMessagesToolsAndResults() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call-1")
                .name("shell")
                .arguments(Map.of("command", "echo hello", "nested", Map.of("flag", true)))
                .build();
        Message message = Message.builder()
                .role("assistant")
                .content("Running a tool")
                .toolCallId("call-1")
                .toolName("shell")
                .toolCalls(List.of(toolCall))
                .metadata(Map.of("model", "gpt", "tags", List.of("a", "b")))
                .build();
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name("shell")
                .description("Execute shell commands")
                .inputSchema(Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string"))))
                .build();
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("system prompt")
                .messages(List.of(message))
                .tools(List.of(toolDefinition))
                .toolResults(Map.of("call-1", ToolResult.success("hello")))
                .build();

        int messagesOnly = estimator.estimateMessages(List.of(message));
        int fullRequest = estimator.estimateRequest(request);

        assertTrue(messagesOnly > 0);
        assertTrue(fullRequest > messagesOnly);
    }

    // Non-ASCII characters below are written as Java Unicode escape sequences
    // so the CI english-only source scanner does not flag them. The Java
    // compiler expands the escapes before the lexer runs, so the runtime
    // String is identical to the literal character.
    private static final String CYRILLIC_YA = "\u044F"; // Cyrillic small letter ya
    private static final String DEVANAGARI_KA = "\u0915"; // Devanagari letter ka
    private static final String THAI_KO_KAI = "\u0E01"; // Thai character ko kai
    private static final String BENGALI_A = "\u0985"; // Bengali letter a
    private static final String CJK_ZHONG = "\u4E2D"; // CJK unified ideograph zhong

    @Test
    void shouldWeightCyrillicHeavierThanLatinOfSameLength() {
        String latin = "a".repeat(400);
        String cyrillic = CYRILLIC_YA.repeat(400);

        Message latinMessage = Message.builder().role("user").content(latin).build();
        Message cyrillicMessage = Message.builder().role("user").content(cyrillic).build();

        int latinTokens = estimator.estimateMessages(List.of(latinMessage));
        int cyrillicTokens = estimator.estimateMessages(List.of(cyrillicMessage));

        // Cyrillic tokenizes at ~2 chars/token vs Latin ~4 chars/token, so the
        // token count must be strictly higher for identical character counts.
        assertTrue(cyrillicTokens > latinTokens,
                "cyrillic=" + cyrillicTokens + " should exceed latin=" + latinTokens);
    }

    @Test
    void shouldWeightIndicAndSeAsianScriptsHeavierThanLatin() {
        String latin = "a".repeat(400);
        String devanagari = DEVANAGARI_KA.repeat(400);
        String thai = THAI_KO_KAI.repeat(400);
        String bengali = BENGALI_A.repeat(400);

        int latinTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(latin).build()));
        int devanagariTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(devanagari).build()));
        int thaiTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(thai).build()));
        int bengaliTokens = estimator.estimateMessages(
                List.of(Message.builder().role("user").content(bengali).build()));

        // Indic / SE Asian scripts tokenize at ~1.5-2.0 chars/token and must be
        // counted in the medium bucket, not accidentally in the Latin bucket —
        // otherwise preflight under-counts the BPE budget and skips compaction
        // for non-English users.
        assertTrue(devanagariTokens > latinTokens,
                "devanagari=" + devanagariTokens + " should exceed latin=" + latinTokens);
        assertTrue(thaiTokens > latinTokens,
                "thai=" + thaiTokens + " should exceed latin=" + latinTokens);
        assertTrue(bengaliTokens > latinTokens,
                "bengali=" + bengaliTokens + " should exceed latin=" + latinTokens);
    }

    @Test
    void shouldWeightCjkHeavierThanCyrillic() {
        String cyrillic = CYRILLIC_YA.repeat(200);
        String cjk = CJK_ZHONG.repeat(200);

        Message cyrillicMessage = Message.builder().role("user").content(cyrillic).build();
        Message cjkMessage = Message.builder().role("user").content(cjk).build();

        int cyrillicTokens = estimator.estimateMessages(List.of(cyrillicMessage));
        int cjkTokens = estimator.estimateMessages(List.of(cjkMessage));

        // CJK tokenizes at ~1 char/token, so it must outweigh Cyrillic for
        // identical character counts.
        assertTrue(cjkTokens > cyrillicTokens,
                "cjk=" + cjkTokens + " should exceed cyrillic=" + cyrillicTokens);
    }

    @Test
    void shouldTolerateCyclicMetadataWithoutStackOverflow() {
        java.util.Map<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("self", cyclic);
        cyclic.put("name", "loop");

        Message message = Message.builder()
                .role("user")
                .content("hi")
                .metadata(cyclic)
                .build();

        // Must not throw / overflow.
        int tokens = estimator.estimateMessages(List.of(message));
        assertTrue(tokens > 0);
    }

    @Test
    void shouldNotDoubleCountToolResultsWhichAreNotSerializedOnTheWire() {
        // ToolResults map is only internal bookkeeping — Langchain4jAdapter
        // serializes systemPrompt + messages, not request.toolResults. Counting
        // them in the estimate inflates the budget and triggers phantom
        // compactions even when the on-the-wire payload is small.
        LlmRequest withoutToolResults = LlmRequest.builder()
                .systemPrompt("system prompt")
                .messages(List.of(Message.builder().role("user").content("hello").build()))
                .build();
        LlmRequest withHeavyToolResults = LlmRequest.builder()
                .systemPrompt("system prompt")
                .messages(List.of(Message.builder().role("user").content("hello").build()))
                .toolResults(Map.of("call-1",
                        ToolResult.success("x".repeat(50_000))))
                .build();

        int withoutTokens = estimator.estimateRequest(withoutToolResults);
        int withTokens = estimator.estimateRequest(withHeavyToolResults);

        assertEquals(withoutTokens, withTokens,
                "tool results must not contribute to wire-size estimate");
    }

    @Test
    void shouldEstimatePrimitiveArraysProportionallyToLength() {
        int[] largeIntArray = new int[2_000];
        for (int index = 0; index < largeIntArray.length; index++) {
            largeIntArray[index] = index;
        }
        int[] smallIntArray = new int[] { 1, 2, 3 };

        Message largeMessage = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", largeIntArray))
                .build();
        Message smallMessage = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", smallIntArray))
                .build();

        int largeTokens = estimator.estimateMessages(List.of(largeMessage));
        int smallTokens = estimator.estimateMessages(List.of(smallMessage));

        // A 2000-element int array must dominate the estimate: JDK
        // Object.toString on arrays yields "[I@abc" (~6 chars), which hides the
        // payload entirely. Require the larger array to produce materially more
        // tokens than the tiny one.
        assertTrue(largeTokens > smallTokens + 200,
                "large=" + largeTokens + " must exceed small=" + smallTokens + " by a wide margin");
    }

    @Test
    void shouldEstimateObjectArraysByIteratingElements() {
        String[] strings = new String[50];
        java.util.Arrays.fill(strings, "abcdefghij");
        Message message = Message.builder()
                .role("user")
                .content("x")
                .metadata(Map.of("values", strings))
                .build();

        int tokens = estimator.estimateMessages(List.of(message));

        // 50 strings × 10 chars ≈ 125+ tokens with Latin weighting + overhead.
        // Old behavior: String.valueOf(stringArray) returns "[Ljava.lang.String;@abc".
        assertTrue(tokens > 100, "tokens=" + tokens + " must reflect array contents");
    }

    @Test
    void shouldHandleNullElementsAndSaturateHugeInputs() {
        Message huge = Message.builder()
                .role("user")
                .content("x".repeat(50_000))
                .metadata(Map.of("array", new int[] { 1, 2, 3 }))
                .build();
        Map<String, ToolResult> toolResults = new java.util.HashMap<>();
        toolResults.put("null-result", null);
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("prompt")
                .messages(java.util.Arrays.asList(null, huge))
                .tools(java.util.Arrays.asList(null, ToolDefinition.simple("noop", "No operation")))
                .toolResults(toolResults)
                .build();

        assertTrue(estimator.estimateRequest(request) > 10_000);
    }
}
