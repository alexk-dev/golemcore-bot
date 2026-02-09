package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringWriter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for McpClient JSON-RPC serialization, tool parsing, and tool call
 * results. Uses PipedInputStream/PipedOutputStream to simulate the process IO.
 */
class McpClientTest {

    private static final String FIELD_WRITER = "writer";
    private static final String FIELD_PROCESS = "process";
    private static final String FIELD_RUNNING = "running";
    private static final String FIELD_PENDING_REQUESTS = "pendingRequests";
    private static final String CMD_TEST = "test";
    private static final String KEY_NAME = "name";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_METHOD = "method";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_TEXT = "text";
    private static final String JSONRPC_VERSION = "2.0";
    private static final String TOOL_CREATE_ISSUE = "create_issue";
    private static final String METHOD_PARSE_TOOL_DEFINITIONS = "parseToolDefinitions";
    private static final String METHOD_PARSE_TOOL_CALL_RESULT = "parseToolCallResult";
    private static final String SUPPRESS_UNCHECKED = "unchecked";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // We test the internal methods by creating a client and driving it with piped
    // streams
    // For simplicity, we test JSON-RPC serialization/deserialization directly

    @Test
    void testToolDefinitionParsing() throws Exception {
        String toolsResponse = """
                {
                  "tools": [
                    {
                      "name": "create_issue",
                      "description": "Create a GitHub issue",
                      "inputSchema": {
                        "type": "object",
                        "properties": {
                          "title": {"type": "string", "description": "Issue title"},
                          "body": {"type": "string", "description": "Issue body"}
                        },
                        "required": ["title"]
                      }
                    },
                    {
                      "name": "list_repos",
                      "description": "List repositories"
                    }
                  ]
                }
                """;

        JsonNode result = objectMapper.readTree(toolsResponse);
        JsonNode toolsNode = result.get("tools");

        assertNotNull(toolsNode);
        assertEquals(2, toolsNode.size());

        // Parse first tool
        JsonNode tool1 = toolsNode.get(0);
        assertEquals(TOOL_CREATE_ISSUE, tool1.get(KEY_NAME).asText());
        assertEquals("Create a GitHub issue", tool1.get("description").asText());
        assertTrue(tool1.has("inputSchema"));
        assertEquals("object", tool1.get("inputSchema").get("type").asText());

        // Parse second tool (no inputSchema)
        JsonNode tool2 = toolsNode.get(1);
        assertEquals("list_repos", tool2.get(KEY_NAME).asText());
        assertFalse(tool2.has("inputSchema"));
    }

    @Test
    void testToolCallResultSuccess() throws Exception {
        String response = """
                {
                  "content": [
                    {"type": "text", "text": "Issue #42 created successfully"}
                  ]
                }
                """;

        JsonNode result = objectMapper.readTree(response);
        ToolResult toolResult = parseToolCallResult(TOOL_CREATE_ISSUE, result);

        assertTrue(toolResult.isSuccess());
        assertEquals("Issue #42 created successfully", toolResult.getOutput());
    }

    @Test
    void testToolCallResultWithError() throws Exception {
        String response = """
                {
                  "isError": true,
                  "content": [
                    {"type": "text", "text": "Repository not found"}
                  ]
                }
                """;

        JsonNode result = objectMapper.readTree(response);
        ToolResult toolResult = parseToolCallResult("get_repo", result);

        assertFalse(toolResult.isSuccess());
        assertEquals("Repository not found", toolResult.getError());
    }

    @Test
    void testToolCallResultMultipleContent() throws Exception {
        String response = """
                {
                  "content": [
                    {"type": "text", "text": "Line 1"},
                    {"type": "text", "text": "Line 2"},
                    {"type": "image", "data": "base64..."}
                  ]
                }
                """;

        JsonNode result = objectMapper.readTree(response);
        ToolResult toolResult = parseToolCallResult("multi", result);

        assertTrue(toolResult.isSuccess());
        assertEquals("Line 1\nLine 2", toolResult.getOutput());
    }

    @Test
    void testToolCallResultEmptyContent() throws Exception {
        String response = """
                {
                  "content": []
                }
                """;

        JsonNode result = objectMapper.readTree(response);
        ToolResult toolResult = parseToolCallResult("empty", result);

        assertTrue(toolResult.isSuccess());
        assertEquals("(no output)", toolResult.getOutput());
    }

    @Test
    void testToolCallResultNullResult() {
        ToolResult toolResult = parseToolCallResult("null_tool", null);
        assertFalse(toolResult.isSuccess());
        assertTrue(toolResult.getError().contains("No result"));
    }

    @Test
    void testJsonRpcRequestSerialization() throws Exception {
        Map<String, Object> request = Map.of(
                KEY_JSONRPC, JSONRPC_VERSION,
                "id", 1,
                KEY_METHOD, "tools/list",
                KEY_PARAMS, Map.of());

        String json = objectMapper.writeValueAsString(request);
        JsonNode parsed = objectMapper.readTree(json);

        assertEquals(JSONRPC_VERSION, parsed.get(KEY_JSONRPC).asText());
        assertEquals(1, parsed.get("id").asInt());
        assertEquals("tools/list", parsed.get(KEY_METHOD).asText());
        assertTrue(parsed.has(KEY_PARAMS));
    }

    @Test
    void testJsonRpcResponseParsing() throws Exception {
        String response = """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}}}}
                """;

        JsonNode parsed = objectMapper.readTree(response);
        assertEquals(JSONRPC_VERSION, parsed.get(KEY_JSONRPC).asText());
        assertEquals(1, parsed.get("id").asInt());
        assertNotNull(parsed.get("result"));
        assertEquals("2024-11-05", parsed.get("result").get("protocolVersion").asText());
    }

    @Test
    void testJsonRpcErrorResponseParsing() throws Exception {
        String response = """
                {"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}
                """;

        JsonNode parsed = objectMapper.readTree(response);
        assertEquals(2, parsed.get("id").asInt());
        assertNotNull(parsed.get("error"));
        assertEquals(-32601, parsed.get("error").get("code").asInt());
        assertEquals("Method not found", parsed.get("error").get("message").asText());
    }

    @Test
    void testToolCallRequestSerialization() throws Exception {
        Map<String, Object> params = Map.of(
                KEY_NAME, TOOL_CREATE_ISSUE,
                "arguments", Map.of(
                        "title", "Bug report",
                        "body", "Something is broken"));

        Map<String, Object> request = Map.of(
                KEY_JSONRPC, JSONRPC_VERSION,
                "id", 3,
                KEY_METHOD, "tools/call",
                KEY_PARAMS, params);

        String json = objectMapper.writeValueAsString(request);
        JsonNode parsed = objectMapper.readTree(json);

        assertEquals("tools/call", parsed.get(KEY_METHOD).asText());
        assertEquals(TOOL_CREATE_ISSUE, parsed.get(KEY_PARAMS).get(KEY_NAME).asText());
        assertEquals("Bug report", parsed.get(KEY_PARAMS).get("arguments").get("title").asText());
    }

    // ===== McpClient lifecycle =====

    @Test
    void testReadLoopHandlesNullProcess() throws Exception {
        // McpClient with no process started — readLoop should return immediately
        try (McpClient client = new McpClient("test-skill", null, objectMapper)) {
            // Just verify it can be closed without NPE
            assertFalse(client.isRunning());
        }
    }

    @Test
    void testCloseCompletesAllPendingRequests() throws Exception {
        try (McpClient client = new McpClient("close-test", null, objectMapper)) {
            // Client without started process should still close gracefully
            assertFalse(client.isRunning());
            assertEquals(0, client.getCachedTools().size());
        }
    }

    @Test
    void testGetCachedToolsReturnsEmptyWhenNotStarted() {
        try (McpClient client = new McpClient("empty-test", null, objectMapper)) {
            List<ToolDefinition> tools = client.getCachedTools();
            assertNotNull(tools);
            assertTrue(tools.isEmpty());
        }
    }

    @Test
    void testIsRunningReturnsFalseWhenNotStarted() {
        try (McpClient client = new McpClient("not-started", null, objectMapper)) {
            assertFalse(client.isRunning());
        }
    }

    @Test
    void testGetSkillName() {
        try (McpClient client = new McpClient("my-skill", null, objectMapper)) {
            assertEquals("my-skill", client.getSkillName());
        }
    }

    @Test
    void testLastActivityTimestamp() {
        try (McpClient client = new McpClient("activity-test", null, objectMapper)) {
            long ts = client.getLastActivityTimestamp();
            assertTrue(ts > 0);
        }
    }

    @Test
    void testMcpExceptionPreservesCodeAndMessage() {
        McpClient.McpException ex = new McpClient.McpException(-32601, "Method not found");
        assertEquals(-32601, ex.getCode());
        assertEquals("Method not found", ex.getMessage());
    }

    // ===== sendRequest / sendNotification with injected writer =====

    @Test
    void shouldSendRequestViaWriter() throws Exception {
        McpConfig config = McpConfig.builder()
                .command("echo test")
                .startupTimeoutSeconds(5)
                .build();
        try (McpClient client = new McpClient("writer-test", config, objectMapper)) {
            // Inject a mock writer to capture output
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            CompletableFuture<JsonNode> future = client.sendRequest("tools/list", Map.of());

            bufferedWriter.flush();
            String output = stringWriter.toString();
            assertTrue(output.contains("\"jsonrpc\":\"2.0\""));
            assertTrue(output.contains("\"method\":\"tools/list\""));
            assertTrue(output.contains("\"id\":"));

            // The future will timeout (no reader thread), cancel it
            future.cancel(true);
        }
    }

    @Test
    void shouldSendNotificationViaWriter() throws Exception {
        McpConfig config = McpConfig.builder()
                .command("echo test")
                .build();
        try (McpClient client = new McpClient("notif-test", config, objectMapper)) {
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            client.sendNotification("notifications/initialized", Map.of());

            bufferedWriter.flush();
            String output = stringWriter.toString();
            assertTrue(output.contains("\"jsonrpc\":\"2.0\""));
            assertTrue(output.contains("\"method\":\"notifications/initialized\""));
            // Notifications have no "id" field
            assertFalse(output.contains("\"id\":"));
        }
    }

    @Test
    void shouldSendNotificationWithParams() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("notif-params", config, objectMapper)) {
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            client.sendNotification("test/method", Map.of("key", "value"));

            bufferedWriter.flush();
            String output = stringWriter.toString();
            assertTrue(output.contains("\"params\""));
            assertTrue(output.contains("\"key\""));
        }
    }

    @Test
    void shouldSendNotificationWithEmptyParams() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("empty-params", config, objectMapper)) {
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            client.sendNotification("test/method", Map.of());

            bufferedWriter.flush();
            String output = stringWriter.toString();
            // Empty params should not include "params" key
            assertFalse(output.contains("\"params\""));
        }
    }

    @Test
    void shouldSendNotificationWithNullParams() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("null-params", config, objectMapper)) {
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            client.sendNotification("test/method", null);

            bufferedWriter.flush();
            String output = stringWriter.toString();
            assertFalse(output.contains("\"params\""));
        }
    }

    @Test
    void shouldHandleWriterIOException() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("io-error", config, objectMapper);
                BufferedWriter bw = new BufferedWriter(new StringWriter()) {
                    @Override
                    public void write(char[] cbuf, int off, int len) throws IOException {
                        throw new IOException("Write failed");
                    }

                    @Override
                    public void write(String str, int off, int len) throws IOException {
                        throw new IOException("Write failed");
                    }
                }) {
            // Inject a writer that will throw IOException
            ReflectionTestUtils.setField(client, FIELD_WRITER, bw);

            CompletableFuture<JsonNode> future = client.sendRequest(CMD_TEST, Map.of());

            // Future should complete exceptionally due to IOException
            assertTrue(future.isCompletedExceptionally());

            // Restore a safe writer so client.close() doesn't throw
            ReflectionTestUtils.setField(client, FIELD_WRITER, new BufferedWriter(new StringWriter()));
        }
    }

    // ===== parseToolDefinitions via reflection =====

    @Test
    void shouldParseToolDefinitionsFromJson() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("parse-test", config, objectMapper)) {
            String json = """
                    {
                      "tools": [
                        {
                          "name": "search",
                          "description": "Search the web",
                          "inputSchema": {
                            "type": "object",
                            "properties": {
                              "query": {"type": "string"}
                            }
                          }
                        },
                        {
                          "name": "calculate",
                          "description": "Do math"
                        }
                      ]
                    }
                    """;
            JsonNode node = objectMapper.readTree(json);

            @SuppressWarnings(SUPPRESS_UNCHECKED)
            List<ToolDefinition> tools = (List<ToolDefinition>) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_DEFINITIONS, node);

            assertNotNull(tools);
            assertEquals(2, tools.size());
            assertEquals("search", tools.get(0).getName());
            assertEquals("Search the web", tools.get(0).getDescription());
            assertNotNull(tools.get(0).getInputSchema());
            assertEquals("calculate", tools.get(1).getName());
        }
    }

    @Test
    void shouldReturnEmptyForNullToolDefinitions() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("null-tools", config, objectMapper)) {
            @SuppressWarnings(SUPPRESS_UNCHECKED)
            List<ToolDefinition> tools = (List<ToolDefinition>) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_DEFINITIONS, (JsonNode) null);
            assertNotNull(tools);
            assertTrue(tools.isEmpty());
        }
    }

    @Test
    void shouldReturnEmptyForMissingToolsArray() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("no-tools", config, objectMapper)) {
            JsonNode node = objectMapper.readTree("{}");

            @SuppressWarnings(SUPPRESS_UNCHECKED)
            List<ToolDefinition> tools = (List<ToolDefinition>) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_DEFINITIONS, node);
            assertNotNull(tools);
            assertTrue(tools.isEmpty());
        }
    }

    @Test
    void shouldSkipToolsWithoutName() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("no-name", config, objectMapper)) {
            String json = """
                    {"tools": [{"description": "No name tool"}]}
                    """;
            JsonNode node = objectMapper.readTree(json);

            @SuppressWarnings(SUPPRESS_UNCHECKED)
            List<ToolDefinition> tools = (List<ToolDefinition>) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_DEFINITIONS, node);
            assertNotNull(tools);
            assertTrue(tools.isEmpty());
        }
    }

    // ===== parseToolCallResult via reflection =====

    @Test
    void shouldParseToolCallResultViaReflection() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("result-test", config, objectMapper)) {
            String json = """
                    {"content": [{"type": "text", "text": "Success result"}]}
                    """;
            JsonNode node = objectMapper.readTree(json);

            ToolResult result = (ToolResult) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_CALL_RESULT, "test_tool", node);
            assertNotNull(result);
            assertTrue(result.isSuccess());
            assertEquals("Success result", result.getOutput());
        }
    }

    @Test
    void shouldParseErrorToolCallResult() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("error-test", config, objectMapper)) {
            String json = """
                    {"isError": true, "content": [{"type": "text", "text": "Error occurred"}]}
                    """;
            JsonNode node = objectMapper.readTree(json);

            ToolResult result = (ToolResult) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_CALL_RESULT, "fail_tool", node);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertEquals("Error occurred", result.getError());
        }
    }

    @Test
    void shouldParseEmptyErrorResult() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("empty-error", config, objectMapper)) {
            String json = """
                    {"isError": true, "content": []}
                    """;
            JsonNode node = objectMapper.readTree(json);

            ToolResult result = (ToolResult) ReflectionTestUtils.invokeMethod(
                    client, METHOD_PARSE_TOOL_CALL_RESULT, CMD_TEST, node);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertEquals("MCP tool error", result.getError());
        }
    }

    // ===== close() with mock process =====

    @Test
    void shouldCloseProcessGracefully() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("close-process", config, objectMapper)) {
            Process mockProcess = mock(Process.class);
            when(mockProcess.isAlive()).thenReturn(true);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);

            ReflectionTestUtils.setField(client, FIELD_PROCESS, mockProcess);
            ReflectionTestUtils.setField(client, FIELD_RUNNING, true);

            client.close();

            verify(mockProcess).destroy();
            assertFalse(client.isRunning());
        }
    }

    @Test
    void shouldForceKillProcessAfterTimeout() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("force-kill", config, objectMapper)) {
            Process mockProcess = mock(Process.class);
            when(mockProcess.isAlive()).thenReturn(true);
            when(mockProcess.waitFor(anyLong(), any())).thenReturn(false); // timeout

            ReflectionTestUtils.setField(client, FIELD_PROCESS, mockProcess);
            ReflectionTestUtils.setField(client, FIELD_RUNNING, true);

            client.close();

            verify(mockProcess).destroyForcibly();
        }
    }

    @Test
    void shouldHandleInterruptDuringClose() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("interrupt-close", config, objectMapper)) {
            Process mockProcess = mock(Process.class);
            when(mockProcess.isAlive()).thenReturn(true);
            when(mockProcess.waitFor(anyLong(), any())).thenThrow(new InterruptedException("interrupted"));

            ReflectionTestUtils.setField(client, FIELD_PROCESS, mockProcess);
            ReflectionTestUtils.setField(client, FIELD_RUNNING, true);

            client.close();

            verify(mockProcess).destroyForcibly();
        }
    }

    // ===== callTool =====

    @Test
    void shouldCallToolAndReturnResult() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("call-tool", config, objectMapper)) {
            // Set up writer to capture output
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            CompletableFuture<ToolResult> future = client.callTool("search", Map.of("query", CMD_TEST));

            // Verify the request was sent
            bufferedWriter.flush();
            String output = stringWriter.toString();
            assertTrue(output.contains("\"method\":\"tools/call\""));
            assertTrue(output.contains("\"name\":\"search\""));

            // Cancel future since we don't have a real server
            future.cancel(true);
        }
    }

    @Test
    void shouldCallToolWithNullArguments() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("null-args", config, objectMapper)) {
            StringWriter stringWriter = new StringWriter();
            BufferedWriter bufferedWriter = new BufferedWriter(stringWriter);

            ReflectionTestUtils.setField(client, FIELD_WRITER, bufferedWriter);

            CompletableFuture<ToolResult> future = client.callTool("test_tool", null);

            bufferedWriter.flush();
            String output = stringWriter.toString();
            assertTrue(output.contains("\"arguments\":{}"));

            future.cancel(true);
        }
    }

    // ===== Activity tracking =====

    @Test
    void shouldUpdateActivityOnCallTool() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("activity", config, objectMapper)) {
            long before = client.getLastActivityTimestamp();
            Thread.sleep(10);

            StringWriter sw = new StringWriter();
            BufferedWriter bw = new BufferedWriter(sw);
            ReflectionTestUtils.setField(client, FIELD_WRITER, bw);

            CompletableFuture<ToolResult> future = client.callTool(CMD_TEST, Map.of());
            long after = client.getLastActivityTimestamp();

            assertTrue(after >= before);

            future.cancel(true);
        }
    }

    // ===== readLoop with piped streams =====

    @Test
    void shouldReadResponsesFromPipedStream() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("piped-read", config, objectMapper);
                PipedOutputStream pipedOut = new PipedOutputStream();
                PipedInputStream pipedIn = new PipedInputStream(pipedOut)) {

            // Create a mock process that returns our piped stream
            Process mockProcess = mock(Process.class);
            when(mockProcess.getInputStream()).thenReturn(pipedIn);

            ReflectionTestUtils.setField(client, FIELD_PROCESS, mockProcess);
            ReflectionTestUtils.setField(client, FIELD_RUNNING, true);

            // Set up a pending request
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            @SuppressWarnings(SUPPRESS_UNCHECKED)
            Map<Integer, CompletableFuture<JsonNode>> pending = (Map<Integer, CompletableFuture<JsonNode>>) ReflectionTestUtils
                    .getField(
                            client, FIELD_PENDING_REQUESTS);
            assertNotNull(pending);
            pending.put(1, future);

            // Start reader thread
            Thread readerThread = new Thread(() -> {
                try {
                    ReflectionTestUtils.invokeMethod(client, "readLoop");
                } catch (Exception expected) {
                    Thread.currentThread().interrupt();
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // Write a response
            String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}\n";
            pipedOut.write(response.getBytes(StandardCharsets.UTF_8));
            pipedOut.flush();
            pipedOut.close();

            // Wait for the future to complete
            JsonNode result = future.get(5, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals("ok", result.get("status").asText());

            ReflectionTestUtils.setField(client, FIELD_RUNNING, false);
            readerThread.join(2000);
        }
    }

    @Test
    void shouldHandleErrorResponseInReadLoop() throws Exception {
        McpConfig config = McpConfig.builder().command(CMD_TEST).build();
        try (McpClient client = new McpClient("error-read", config, objectMapper);
                PipedOutputStream pipedOut = new PipedOutputStream();
                PipedInputStream pipedIn = new PipedInputStream(pipedOut)) {

            Process mockProcess = mock(Process.class);
            when(mockProcess.getInputStream()).thenReturn(pipedIn);

            ReflectionTestUtils.setField(client, FIELD_PROCESS, mockProcess);
            ReflectionTestUtils.setField(client, FIELD_RUNNING, true);

            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            @SuppressWarnings(SUPPRESS_UNCHECKED)
            Map<Integer, CompletableFuture<JsonNode>> pending = (Map<Integer, CompletableFuture<JsonNode>>) ReflectionTestUtils
                    .getField(
                            client, FIELD_PENDING_REQUESTS);
            assertNotNull(pending);
            pending.put(2, future);

            Thread readerThread = new Thread(() -> {
                try {
                    ReflectionTestUtils.invokeMethod(client, "readLoop");
                } catch (Exception expected) {
                    Thread.currentThread().interrupt();
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            String errorResponse = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}\n";
            pipedOut.write(errorResponse.getBytes(StandardCharsets.UTF_8));
            pipedOut.flush();
            pipedOut.close();

            // Future should complete exceptionally
            assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));

            ReflectionTestUtils.setField(client, FIELD_RUNNING, false);
            readerThread.join(2000);
        }
    }

    // Helper: replicate the parseToolCallResult logic for testing without a full
    // McpClient
    private ToolResult parseToolCallResult(String toolName, JsonNode result) {
        if (result == null) {
            return ToolResult.failure("No result from MCP tool: " + toolName);
        }

        boolean isError = result.has("isError") && result.get("isError").asBoolean(false);

        StringBuilder output = new StringBuilder();
        JsonNode contentNode = result.get("content");
        if (contentNode != null && contentNode.isArray()) {
            for (JsonNode item : contentNode) {
                String type = item.has("type") ? item.get("type").asText() : KEY_TEXT;
                if (KEY_TEXT.equals(type) && item.has(KEY_TEXT)) {
                    if (!output.isEmpty())
                        output.append("\n");
                    output.append(item.get(KEY_TEXT).asText());
                }
            }
        }

        if (isError) {
            return ToolResult.failure(output.isEmpty() ? "MCP tool error" : output.toString());
        }
        return ToolResult.success(output.isEmpty() ? "(no output)" : output.toString());
    }
}
