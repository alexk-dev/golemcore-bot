package me.golemcore.bot.adapter.outbound.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesRequestBuilderTest {

    private ObjectMapper objectMapper;
    private ResponsesRequestBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        builder = new ResponsesRequestBuilder(objectMapper);
    }

    // ===== Model and basic fields =====

    @Test
    void shouldStripProviderPrefixFromModel() {
        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-5.4")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertEquals("gpt-5.4", body.get("model").asText());
    }

    @Test
    void shouldSetStreamFlag() {
        LlmRequest request = LlmRequest.builder().model("gpt-5.1").build();

        ObjectNode bodyStreaming = builder.buildRequestBody(request, true);
        ObjectNode bodySync = builder.buildRequestBody(request, false);

        assertTrue(bodyStreaming.get("stream").asBoolean());
        assertFalse(bodySync.get("stream").asBoolean());
    }

    @Test
    void shouldIncludeTemperature() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .temperature(0.9)
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertEquals(0.9, body.get("temperature").asDouble(), 0.001);
    }

    @Test
    void shouldIncludeMaxOutputTokens() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .maxTokens(4096)
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertEquals(4096, body.get("max_output_tokens").asInt());
    }

    @Test
    void shouldOmitMaxOutputTokensWhenNull() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertFalse(body.has("max_output_tokens"));
    }

    // ===== Reasoning effort =====

    @Test
    void shouldMapReasoningEffortToNestedObject() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .reasoningEffort("medium")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertTrue(body.has("reasoning"));
        assertEquals("medium", body.get("reasoning").get("effort").asText());
    }

    @Test
    void shouldOmitReasoningWhenNull() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertFalse(body.has("reasoning"));
    }

    @Test
    void shouldOmitReasoningWhenBlank() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .reasoningEffort("  ")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertFalse(body.has("reasoning"));
    }

    // ===== System prompt → developer role =====

    @Test
    void shouldMapSystemPromptToDeveloperRole() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .systemPrompt("You are a helpful assistant")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        assertEquals("developer", input.get(0).get("role").asText());
        assertEquals("You are a helpful assistant", input.get(0).get("content").asText());
    }

    @Test
    void shouldMapSystemRoleMessageToDeveloper() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("system").content("System instructions").build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        assertEquals("developer", input.get(0).get("role").asText());
        assertEquals("System instructions", input.get(0).get("content").asText());
    }

    // ===== User and assistant messages =====

    @Test
    void shouldMapUserMessages() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("user").content("Hello").build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        assertEquals("user", input.get(0).get("role").asText());
        assertEquals("Hello", input.get(0).get("content").asText());
    }

    @Test
    void shouldMapAssistantTextMessage() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("assistant").content("Hi there").build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        assertEquals("assistant", input.get(0).get("role").asText());
        assertEquals("Hi there", input.get(0).get("content").asText());
    }

    @Test
    void shouldMapUnknownRoleToUser() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("custom_role").content("Unknown").build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        assertEquals("user", input.get(0).get("role").asText());
    }

    // ===== Tool calls =====

    @Test
    void shouldMapAssistantToolCallsToFunctionCallItems() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call_123")
                .name("get_weather")
                .arguments(Map.of("city", "NYC"))
                .build();
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder()
                                .role("assistant")
                                .toolCalls(List.of(toolCall))
                                .build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        JsonNode callItem = input.get(0);
        assertEquals("function_call", callItem.get("type").asText());
        assertEquals("call_123", callItem.get("call_id").asText());
        assertEquals("get_weather", callItem.get("name").asText());
        assertTrue(callItem.get("arguments").asText().contains("NYC"));
    }

    @Test
    void shouldEmitAssistantTextBeforeToolCalls() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call_1")
                .name("search")
                .arguments(Map.of("q", "test"))
                .build();
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder()
                                .role("assistant")
                                .content("Let me search for that")
                                .toolCalls(List.of(toolCall))
                                .build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(2, input.size());
        assertEquals("assistant", input.get(0).get("role").asText());
        assertEquals("Let me search for that", input.get(0).get("content").asText());
        assertEquals("function_call", input.get(1).get("type").asText());
    }

    // ===== Tool results =====

    @Test
    void shouldMapToolResultToFunctionCallOutput() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder()
                                .role("tool")
                                .toolCallId("call_123")
                                .content("{\"temp\": 72}")
                                .build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals(1, input.size());
        assertEquals("function_call_output", input.get(0).get("type").asText());
        assertEquals("call_123", input.get(0).get("call_id").asText());
        assertEquals("{\"temp\": 72}", input.get(0).get("output").asText());
    }

    // ===== Tools =====

    @Test
    void shouldBuildToolDefinitions() {
        ToolDefinition tool = ToolDefinition.builder()
                .name("get_weather")
                .description("Get weather for a city")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string"))))
                .build();
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .tools(List.of(tool))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode tools = body.get("tools");

        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals("get_weather", tools.get(0).get("function").get("name").asText());
        assertEquals("Get weather for a city", tools.get(0).get("function").get("description").asText());
        assertTrue(tools.get(0).get("function").has("parameters"));
    }

    @Test
    void shouldOmitToolsWhenEmpty() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);

        assertFalse(body.has("tools"));
    }

    // ===== Full conversation round-trip =====

    @Test
    void shouldBuildFullConversationWithToolRoundTrip() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call_abc")
                .name("read_file")
                .arguments(Map.of("path", "/tmp/test.txt"))
                .build();

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-5.4")
                .systemPrompt("You are a coding assistant")
                .reasoningEffort("high")
                .maxTokens(8192)
                .temperature(0.3)
                .messages(List.of(
                        Message.builder().role("user").content("Read the file").build(),
                        Message.builder().role("assistant").toolCalls(List.of(toolCall)).build(),
                        Message.builder().role("tool").toolCallId("call_abc").content("file contents here").build(),
                        Message.builder().role("assistant").content("The file contains...").build()))
                .tools(List.of(ToolDefinition.builder()
                        .name("read_file")
                        .description("Read a file")
                        .inputSchema(Map.of("type", "object"))
                        .build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, true);

        assertEquals("gpt-5.4", body.get("model").asText());
        assertTrue(body.get("stream").asBoolean());
        assertEquals("high", body.get("reasoning").get("effort").asText());
        assertEquals(8192, body.get("max_output_tokens").asInt());

        JsonNode input = body.get("input");
        // developer + user + function_call + function_call_output + assistant
        assertEquals(5, input.size());
        assertEquals("developer", input.get(0).get("role").asText());
        assertEquals("user", input.get(1).get("role").asText());
        assertEquals("function_call", input.get(2).get("type").asText());
        assertEquals("function_call_output", input.get(3).get("type").asText());
        assertEquals("assistant", input.get(4).get("role").asText());
    }

    // ===== Null content handling =====

    @Test
    void shouldHandleNullContentGracefully() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("user").content(null).build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals("", input.get(0).get("content").asText());
    }

    @Test
    void shouldHandleNullToolCallId() {
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("tool").toolCallId(null).content("result").build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals("", input.get(0).get("call_id").asText());
    }

    @Test
    void shouldHandleEmptyToolArguments() {
        Message.ToolCall toolCall = Message.ToolCall.builder()
                .id("call_1")
                .name("no_args_tool")
                .build();
        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.1")
                .messages(List.of(
                        Message.builder().role("assistant").toolCalls(List.of(toolCall)).build()))
                .build();

        ObjectNode body = builder.buildRequestBody(request, false);
        JsonNode input = body.get("input");

        assertEquals("{}", input.get(0).get("arguments").asText());
    }
}
