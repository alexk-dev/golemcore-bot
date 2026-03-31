package me.golemcore.bot.adapter.outbound.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesEventParserTest {

    private ResponsesEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new ResponsesEventParser(new ObjectMapper());
    }

    // ===== SSE: response.output_text.delta =====

    @Test
    void shouldParseTextDeltaEvent() {
        String data = """
                {"delta": "Hello "}
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_text.delta", data);

        assertNotNull(chunk);
        assertEquals("Hello ", chunk.getText());
        assertFalse(chunk.isDone());
    }

    @Test
    void shouldHandleEmptyTextDelta() {
        String data = """
                {"delta": ""}
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_text.delta", data);

        assertNotNull(chunk);
        assertEquals("", chunk.getText());
        assertFalse(chunk.isDone());
    }

    // ===== SSE: response.function_call_arguments.delta =====

    @Test
    void shouldReturnNullForFunctionCallArgumentsDelta() {
        String data = """
                {"delta": "{\\"city\\":"}
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.function_call_arguments.delta", data);

        assertNull(chunk);
    }

    // ===== SSE: response.output_item.done =====

    @Test
    void shouldParseCompletedFunctionCallItem() {
        String data = """
                {
                  "item": {
                    "type": "function_call",
                    "call_id": "call_abc",
                    "name": "get_weather",
                    "arguments": "{\\"city\\":\\"NYC\\"}"
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_item.done", data);

        assertNotNull(chunk);
        assertFalse(chunk.isDone());
        Message.ToolCall tc = chunk.getToolCall();
        assertNotNull(tc);
        assertEquals("call_abc", tc.getId());
        assertEquals("get_weather", tc.getName());
        assertEquals("NYC", tc.getArguments().get("city"));
    }

    @Test
    void shouldReturnNullForCompletedMessageItem() {
        String data = """
                {
                  "item": {
                    "type": "message",
                    "content": [{"type": "output_text", "text": "Done"}]
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_item.done", data);

        assertNull(chunk);
    }

    @Test
    void shouldReturnNullWhenFunctionCallHasNoName() {
        String data = """
                {
                  "item": {
                    "type": "function_call",
                    "call_id": "call_1",
                    "arguments": "{}"
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_item.done", data);

        assertNull(chunk);
    }

    // ===== SSE: response.completed =====

    @Test
    void shouldParseCompletedEventWithUsage() {
        String data = """
                {
                  "response": {
                    "usage": {
                      "input_tokens": 100,
                      "output_tokens": 50
                    }
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.completed", data);

        assertNotNull(chunk);
        assertTrue(chunk.isDone());
        assertEquals("stop", chunk.getFinishReason());
        assertNotNull(chunk.getUsage());
        assertEquals(100, chunk.getUsage().getInputTokens());
        assertEquals(50, chunk.getUsage().getOutputTokens());
    }

    @Test
    void shouldParseCompletedEventWithTopLevelUsage() {
        String data = """
                {
                  "usage": {
                    "input_tokens": 200,
                    "output_tokens": 80
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.completed", data);

        assertNotNull(chunk);
        assertTrue(chunk.isDone());
        assertNotNull(chunk.getUsage());
        assertEquals(200, chunk.getUsage().getInputTokens());
    }

    // ===== SSE: response.failed =====

    @Test
    void shouldThrowOnFailedEvent() {
        String data = """
                {
                  "error": {
                    "message": "Rate limit exceeded"
                  }
                }
                """;

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> parser.parseStreamEvent("response.failed", data));
        assertTrue(ex.getMessage().contains("Rate limit exceeded"));
    }

    @Test
    void shouldThrowWithNestedResponseErrorOnFailed() {
        String data = """
                {
                  "response": {
                    "error": {
                      "message": "Internal server error"
                    }
                  }
                }
                """;

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> parser.parseStreamEvent("response.failed", data));
        assertTrue(ex.getMessage().contains("Internal server error"));
    }

    @Test
    void shouldThrowWithUnknownErrorWhenNoMessage() {
        String data = """
                {}
                """;

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> parser.parseStreamEvent("response.failed", data));
        assertTrue(ex.getMessage().contains("Unknown error"));
    }

    // ===== SSE: response.incomplete =====

    @Test
    void shouldParseIncompleteEvent() {
        String data = "{}";

        LlmChunk chunk = parser.parseStreamEvent("response.incomplete", data);

        assertNotNull(chunk);
        assertTrue(chunk.isDone());
        assertEquals("length", chunk.getFinishReason());
    }

    // ===== SSE: unknown event types =====

    @Test
    void shouldReturnNullForUnknownEventType() {
        LlmChunk chunk = parser.parseStreamEvent("response.created", "{}");

        assertNull(chunk);
    }

    @Test
    void shouldReturnNullForMalformedJsonInNonCriticalEvent() {
        LlmChunk chunk = parser.parseStreamEvent("response.output_text.delta", "not json");

        assertNull(chunk);
    }

    // ===== Sync: parseSyncResponse =====

    @Test
    void shouldParseSyncResponseWithTextOutput() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {"type": "output_text", "text": "Hello, world!"}
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 10,
                    "output_tokens": 5
                  }
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertEquals("Hello, world!", response.getContent());
        assertEquals("gpt-5.4", response.getModel());
        assertEquals("stop", response.getFinishReason());
        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getInputTokens());
        assertEquals(5, response.getUsage().getOutputTokens());
        assertFalse(response.hasToolCalls());
    }

    @Test
    void shouldParseSyncResponseWithToolCalls() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "function_call",
                      "call_id": "call_xyz",
                      "name": "read_file",
                      "arguments": "{\\"path\\":\\"/tmp/test\\"}"
                    }
                  ],
                  "usage": {
                    "input_tokens": 50,
                    "output_tokens": 20
                  }
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertTrue(response.hasToolCalls());
        List<Message.ToolCall> toolCalls = response.getToolCalls();
        assertEquals(1, toolCalls.size());
        assertEquals("call_xyz", toolCalls.get(0).getId());
        assertEquals("read_file", toolCalls.get(0).getName());
        assertEquals("/tmp/test", toolCalls.get(0).getArguments().get("path"));
    }

    @Test
    void shouldParseSyncResponseWithMixedOutput() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {"type": "output_text", "text": "I'll read that file for you."}
                      ]
                    },
                    {
                      "type": "function_call",
                      "call_id": "call_1",
                      "name": "read_file",
                      "arguments": "{}"
                    }
                  ]
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertEquals("I'll read that file for you.", response.getContent());
        assertTrue(response.hasToolCalls());
        assertEquals("read_file", response.getToolCalls().get(0).getName());
    }

    @Test
    void shouldMapIncompleteStatusToLength() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "incomplete",
                  "output": []
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertEquals("length", response.getFinishReason());
    }

    @Test
    void shouldMapFailedStatusToError() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "failed",
                  "output": []
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertEquals("error", response.getFinishReason());
    }

    @Test
    void shouldDefaultToStopWhenStatusMissing() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "output": []
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void shouldHandleMissingUsageGracefully() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{"type": "output_text", "text": "Hi"}]
                    }
                  ]
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertNull(response.getUsage());
        assertEquals("Hi", response.getContent());
    }

    @Test
    void shouldHandleMultipleTextContentParts() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {"type": "output_text", "text": "Part 1. "},
                        {"type": "output_text", "text": "Part 2."}
                      ]
                    }
                  ]
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        assertEquals("Part 1. Part 2.", response.getContent());
    }

    @Test
    void shouldThrowOnMalformedSyncResponse() {
        assertThrows(RuntimeException.class,
                () -> parser.parseSyncResponse("not valid json"));
    }

    // ===== Argument parsing edge cases =====

    @Test
    void shouldHandleMalformedToolArguments() {
        String data = """
                {
                  "item": {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "broken_tool",
                    "arguments": "not-json"
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_item.done", data);

        assertNotNull(chunk);
        Message.ToolCall tc = chunk.getToolCall();
        assertNotNull(tc);
        assertEquals("broken_tool", tc.getName());
        assertTrue(tc.getArguments().containsKey("_raw"));
        assertEquals("not-json", tc.getArguments().get("_raw"));
    }

    @Test
    void shouldHandleEmptyToolArguments() {
        String data = """
                {
                  "item": {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "no_args",
                    "arguments": ""
                  }
                }
                """;

        LlmChunk chunk = parser.parseStreamEvent("response.output_item.done", data);

        assertNotNull(chunk);
        assertTrue(chunk.getToolCall().getArguments().isEmpty());
    }

    @Test
    void shouldParseToolArgumentsWithVariousTypes() {
        String json = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "function_call",
                      "call_id": "call_1",
                      "name": "typed_tool",
                      "arguments": "{\\"str\\":\\"hello\\",\\"num\\":42,\\"bool\\":true,\\"nil\\":null}"
                    }
                  ]
                }
                """;

        LlmResponse response = parser.parseSyncResponse(json);

        Message.ToolCall tc = response.getToolCalls().get(0);
        assertEquals("hello", tc.getArguments().get("str"));
        assertEquals(42, tc.getArguments().get("num"));
        assertEquals(true, tc.getArguments().get("bool"));
        assertNull(tc.getArguments().get("nil"));
    }
}
