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
