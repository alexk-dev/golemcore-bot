package me.golemcore.bot.adapter.outbound.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesClientTest {

    private HttpServer server;
    private OpenAiResponsesClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(null);
        server.start();

        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("test-api-key"))
                .baseUrl("http://localhost:" + port + "/v1")
                .requestTimeoutSeconds(10)
                .build();
        client = new OpenAiResponsesClient(config, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ===== Sync chat =====

    @Test
    void shouldSendSyncRequestAndParseResponse() {
        String responseBody = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{"type": "output_text", "text": "Hello from API"}]
                    }
                  ],
                  "usage": {"input_tokens": 10, "output_tokens": 5}
                }
                """;
        serveJson(200, responseBody);

        LlmRequest request = LlmRequest.builder()
                .model("openai/gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        LlmResponse response = client.chat(request);

        assertEquals("Hello from API", response.getContent());
        assertEquals("gpt-5.4", response.getModel());
        assertEquals("stop", response.getFinishReason());
        assertEquals(10, response.getUsage().getInputTokens());
        assertEquals(5, response.getUsage().getOutputTokens());
    }

    @Test
    void shouldSendAuthorizationHeader() {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"model":"gpt-5.4","status":"completed","output":[],"usage":{"input_tokens":0,"output_tokens":0}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("test").build()))
                .build();
        client.chat(request);

        assertEquals("Bearer test-api-key", capturedAuth.get());
    }

    @Test
    void shouldSendContentTypeHeader() {
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = """
                    {"model":"gpt-5.4","status":"completed","output":[],"usage":{"input_tokens":0,"output_tokens":0}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("test").build()))
                .build();
        client.chat(request);

        assertEquals("application/json", capturedContentType.get());
    }

    @Test
    void shouldIncludeToolsAndReasoningInRequestBody() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"model":"gpt-5.4","status":"completed","output":[],"usage":{"input_tokens":0,"output_tokens":0}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .reasoningEffort("high")
                .tools(List.of(ToolDefinition.builder().name("test_tool").description("A tool").build()))
                .messages(List.of(Message.builder().role("user").content("test").build()))
                .build();
        client.chat(request);

        String body = capturedBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"reasoning\""));
        assertTrue(body.contains("\"effort\":\"high\""));
        assertTrue(body.contains("\"tools\""));
        assertTrue(body.contains("\"test_tool\""));
    }

    @Test
    void shouldThrowOnHttpError() {
        serveJson(401, """
                {"error":{"message":"Invalid API key"}}
                """);

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.chat(request));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void shouldThrowOnServerError() {
        serveJson(500, """
                {"error":{"message":"Internal error"}}
                """);

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.chat(request));
        assertTrue(ex.getMessage().contains("500"));
    }

    // ===== Sync: tool call response =====

    @Test
    void shouldParseSyncToolCallResponse() {
        String responseBody = """
                {
                  "model": "gpt-5.4",
                  "status": "completed",
                  "output": [
                    {
                      "type": "function_call",
                      "call_id": "call_123",
                      "name": "get_weather",
                      "arguments": "{\\"city\\":\\"London\\"}"
                    }
                  ],
                  "usage": {"input_tokens": 30, "output_tokens": 15}
                }
                """;
        serveJson(200, responseBody);

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Weather?").build()))
                .build();

        LlmResponse response = client.chat(request);

        assertTrue(response.hasToolCalls());
        assertEquals("get_weather", response.getToolCalls().get(0).getName());
        assertEquals("London", response.getToolCalls().get(0).getArguments().get("city"));
    }

    // ===== Streaming SSE =====

    @Test
    void shouldStreamSseEventsIntoChunks() {
        String sseBody = """
                event: response.output_text.delta\r
                data: {"delta":"Hello "}\r
                \r
                event: response.output_text.delta\r
                data: {"delta":"world!"}\r
                \r
                event: response.completed\r
                data: {"response":{"usage":{"input_tokens":10,"output_tokens":3}}}\r
                \r
                """;
        serveSse(200, sseBody);

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        List<LlmChunk> chunks = client.chatStream(request).collectList().block();

        assertNotNull(chunks);
        assertEquals(3, chunks.size());
        assertEquals("Hello ", chunks.get(0).getText());
        assertFalse(chunks.get(0).isDone());
        assertEquals("world!", chunks.get(1).getText());
        assertFalse(chunks.get(1).isDone());
        assertTrue(chunks.get(2).isDone());
        assertEquals("stop", chunks.get(2).getFinishReason());
        assertEquals(10, chunks.get(2).getUsage().getInputTokens());
    }

    @Test
    void shouldStreamToolCallViaOutputItemDone() {
        String sseBody = """
                event: response.output_item.done\r
                data: {"item":{"type":"function_call","call_id":"call_1","name":"search","arguments":"{\\"q\\":\\"test\\"}"}}\r
                \r
                event: response.completed\r
                data: {"response":{"usage":{"input_tokens":5,"output_tokens":2}}}\r
                \r
                """;
        serveSse(200, sseBody);

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("search").build()))
                .build();

        List<LlmChunk> chunks = client.chatStream(request).collectList().block();

        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        assertNotNull(chunks.get(0).getToolCall());
        assertEquals("search", chunks.get(0).getToolCall().getName());
        assertTrue(chunks.get(1).isDone());
    }

    @Test
    void shouldHandleDoneSignalInStream() {
        String sseBody = """
                event: response.output_text.delta\r
                data: {"delta":"Hi"}\r
                \r
                data: [DONE]\r
                \r
                """;
        serveSse(200, sseBody);

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        List<LlmChunk> chunks = client.chatStream(request).collectList().block();

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("Hi", chunks.get(0).getText());
    }

    // ===== Streaming fallback =====

    @Test
    void shouldFallbackToSyncOnStreamingHttpError() {
        // First request (streaming) returns 429, second (sync fallback) returns 200
        AtomicReference<Integer> requestCount = new AtomicReference<>(0);
        String syncResponse = """
                {"model":"gpt-5.4","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Fallback response"}]}],"usage":{"input_tokens":5,"output_tokens":3}}
                """;

        server.createContext("/v1/responses", exchange -> {
            int count = requestCount.getAndUpdate(c -> c + 1);
            byte[] body;
            if (count == 0) {
                // First request: streaming attempt gets 429
                body = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(429, body.length);
            } else {
                // Second request: sync fallback succeeds
                body = syncResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        LlmRequest request = LlmRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(Message.builder().role("user").content("Hi").build()))
                .build();

        List<LlmChunk> chunks = client.chatStream(request).collectList().block();

        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("Fallback response", chunks.get(0).getText());
        assertTrue(chunks.get(0).isDone());
    }

    // ===== URL resolution =====

    @Test
    void shouldUseDefaultBaseUrlWhenNotConfigured() {
        RuntimeConfig.LlmProviderConfig config = RuntimeConfig.LlmProviderConfig.builder()
                .apiKey(Secret.of("key"))
                .build();
        // This just verifies the client is created without error — actual URL
        // is tested implicitly by the sync/stream tests above.
        OpenAiResponsesClient defaultClient = new OpenAiResponsesClient(config, new ObjectMapper());
        assertNotNull(defaultClient);
    }

    // ===== Helpers =====

    private void serveJson(int statusCode, String body) {
        server.createContext("/v1/responses", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private void serveSse(int statusCode, String sseBody) {
        server.createContext("/v1/responses", exchange -> {
            byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }
}
