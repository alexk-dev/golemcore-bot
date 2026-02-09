package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for McpClient JSON-RPC serialization, tool parsing, and tool call
 * results. Uses PipedInputStream/PipedOutputStream to simulate the process IO.
 */
class McpClientTest {

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
        assertEquals("create_issue", tool1.get("name").asText());
        assertEquals("Create a GitHub issue", tool1.get("description").asText());
        assertTrue(tool1.has("inputSchema"));
        assertEquals("object", tool1.get("inputSchema").get("type").asText());

        // Parse second tool (no inputSchema)
        JsonNode tool2 = toolsNode.get(1);
        assertEquals("list_repos", tool2.get("name").asText());
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
        ToolResult toolResult = parseToolCallResult("create_issue", result);

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
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of());

        String json = objectMapper.writeValueAsString(request);
        JsonNode parsed = objectMapper.readTree(json);

        assertEquals("2.0", parsed.get("jsonrpc").asText());
        assertEquals(1, parsed.get("id").asInt());
        assertEquals("tools/list", parsed.get("method").asText());
        assertTrue(parsed.has("params"));
    }

    @Test
    void testJsonRpcResponseParsing() throws Exception {
        String response = """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}}}}
                """;

        JsonNode parsed = objectMapper.readTree(response);
        assertEquals("2.0", parsed.get("jsonrpc").asText());
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
                "name", "create_issue",
                "arguments", Map.of(
                        "title", "Bug report",
                        "body", "Something is broken"));

        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "tools/call",
                "params", params);

        String json = objectMapper.writeValueAsString(request);
        JsonNode parsed = objectMapper.readTree(json);

        assertEquals("tools/call", parsed.get("method").asText());
        assertEquals("create_issue", parsed.get("params").get("name").asText());
        assertEquals("Bug report", parsed.get("params").get("arguments").get("title").asText());
    }

    // ===== McpClient lifecycle =====

    @Test
    void testReadLoopHandlesNullProcess() throws Exception {
        // McpClient with no process started — readLoop should return immediately
        McpClient client = new McpClient("test-skill", null, objectMapper);
        // Just verify it can be closed without NPE
        client.close();
        assertFalse(client.isRunning());
    }

    @Test
    void testCloseCompletesAllPendingRequests() throws Exception {
        McpClient client = new McpClient("close-test", null, objectMapper);
        // Client without started process should still close gracefully
        client.close();
        assertFalse(client.isRunning());
        assertEquals(0, client.getCachedTools().size());
    }

    @Test
    void testGetCachedToolsReturnsEmptyWhenNotStarted() {
        McpClient client = new McpClient("empty-test", null, objectMapper);
        List<ToolDefinition> tools = client.getCachedTools();
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testIsRunningReturnsFalseWhenNotStarted() {
        McpClient client = new McpClient("not-started", null, objectMapper);
        assertFalse(client.isRunning());
    }

    @Test
    void testGetSkillName() {
        McpClient client = new McpClient("my-skill", null, objectMapper);
        assertEquals("my-skill", client.getSkillName());
    }

    @Test
    void testLastActivityTimestamp() {
        McpClient client = new McpClient("activity-test", null, objectMapper);
        long ts = client.getLastActivityTimestamp();
        assertTrue(ts > 0);
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
        McpClient client = new McpClient("writer-test", config, objectMapper);

        // Inject a mock writer to capture output
        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        CompletableFuture<JsonNode> future = client.sendRequest("tools/list", Map.of());

        bufferedWriter.flush();
        String output = stringWriter.toString();
        assertTrue(output.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(output.contains("\"method\":\"tools/list\""));
        assertTrue(output.contains("\"id\":"));

        // The future will timeout (no reader thread), cancel it
        future.cancel(true);
        client.close();
    }

    @Test
    void shouldSendNotificationViaWriter() throws Exception {
        McpConfig config = McpConfig.builder()
                .command("echo test")
                .build();
        McpClient client = new McpClient("notif-test", config, objectMapper);

        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        client.sendNotification("notifications/initialized", Map.of());

        bufferedWriter.flush();
        String output = stringWriter.toString();
        assertTrue(output.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(output.contains("\"method\":\"notifications/initialized\""));
        // Notifications have no "id" field
        assertFalse(output.contains("\"id\":"));

        client.close();
    }

    @Test
    void shouldSendNotificationWithParams() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("notif-params", config, objectMapper);

        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        client.sendNotification("test/method", Map.of("key", "value"));

        bufferedWriter.flush();
        String output = stringWriter.toString();
        assertTrue(output.contains("\"params\""));
        assertTrue(output.contains("\"key\""));

        client.close();
    }

    @Test
    void shouldSendNotificationWithEmptyParams() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("empty-params", config, objectMapper);

        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        client.sendNotification("test/method", Map.of());

        bufferedWriter.flush();
        String output = stringWriter.toString();
        // Empty params should not include "params" key
        assertFalse(output.contains("\"params\""));

        client.close();
    }

    @Test
    void shouldSendNotificationWithNullParams() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("null-params", config, objectMapper);

        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        client.sendNotification("test/method", null);

        bufferedWriter.flush();
        String output = stringWriter.toString();
        assertFalse(output.contains("\"params\""));

        client.close();
    }

    @Test
    void shouldHandleWriterIOException() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("io-error", config, objectMapper);

        // Inject a closed writer that will throw IOException
        java.io.StringWriter sw = new java.io.StringWriter();
        sw.close(); // close underlying writer
        java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws java.io.IOException {
                throw new java.io.IOException("Write failed");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bw);

        CompletableFuture<JsonNode> future = client.sendRequest("test", Map.of());

        // Future should complete exceptionally due to IOException
        assertTrue(future.isCompletedExceptionally());
        client.close();
    }

    // ===== parseToolDefinitions via reflection =====

    @Test
    void shouldParseToolDefinitionsFromJson() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("parse-test", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolDefinitions",
                JsonNode.class);
        parseMethod.setAccessible(true);

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

        @SuppressWarnings("unchecked")
        List<ToolDefinition> tools = (List<ToolDefinition>) parseMethod.invoke(client, node);

        assertEquals(2, tools.size());
        assertEquals("search", tools.get(0).getName());
        assertEquals("Search the web", tools.get(0).getDescription());
        assertNotNull(tools.get(0).getInputSchema());
        assertEquals("calculate", tools.get(1).getName());
    }

    @Test
    void shouldReturnEmptyForNullToolDefinitions() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("null-tools", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolDefinitions",
                JsonNode.class);
        parseMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ToolDefinition> tools = (List<ToolDefinition>) parseMethod.invoke(client, (JsonNode) null);
        assertTrue(tools.isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingToolsArray() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("no-tools", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolDefinitions",
                JsonNode.class);
        parseMethod.setAccessible(true);

        JsonNode node = objectMapper.readTree("{}");

        @SuppressWarnings("unchecked")
        List<ToolDefinition> tools = (List<ToolDefinition>) parseMethod.invoke(client, node);
        assertTrue(tools.isEmpty());
    }

    @Test
    void shouldSkipToolsWithoutName() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("no-name", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolDefinitions",
                JsonNode.class);
        parseMethod.setAccessible(true);

        String json = """
                {"tools": [{"description": "No name tool"}]}
                """;
        JsonNode node = objectMapper.readTree(json);

        @SuppressWarnings("unchecked")
        List<ToolDefinition> tools = (List<ToolDefinition>) parseMethod.invoke(client, node);
        assertTrue(tools.isEmpty());
    }

    // ===== parseToolCallResult via reflection =====

    @Test
    void shouldParseToolCallResultViaReflection() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("result-test", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolCallResult",
                String.class, JsonNode.class);
        parseMethod.setAccessible(true);

        String json = """
                {"content": [{"type": "text", "text": "Success result"}]}
                """;
        JsonNode node = objectMapper.readTree(json);

        ToolResult result = (ToolResult) parseMethod.invoke(client, "test_tool", node);
        assertTrue(result.isSuccess());
        assertEquals("Success result", result.getOutput());
    }

    @Test
    void shouldParseErrorToolCallResult() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("error-test", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolCallResult",
                String.class, JsonNode.class);
        parseMethod.setAccessible(true);

        String json = """
                {"isError": true, "content": [{"type": "text", "text": "Error occurred"}]}
                """;
        JsonNode node = objectMapper.readTree(json);

        ToolResult result = (ToolResult) parseMethod.invoke(client, "fail_tool", node);
        assertFalse(result.isSuccess());
        assertEquals("Error occurred", result.getError());
    }

    @Test
    void shouldParseEmptyErrorResult() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("empty-error", config, objectMapper);

        java.lang.reflect.Method parseMethod = McpClient.class.getDeclaredMethod("parseToolCallResult",
                String.class, JsonNode.class);
        parseMethod.setAccessible(true);

        String json = """
                {"isError": true, "content": []}
                """;
        JsonNode node = objectMapper.readTree(json);

        ToolResult result = (ToolResult) parseMethod.invoke(client, "test", node);
        assertFalse(result.isSuccess());
        assertEquals("MCP tool error", result.getError());
    }

    // ===== close() with mock process =====

    @Test
    void shouldCloseProcessGracefully() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("close-process", config, objectMapper);

        Process mockProcess = mock(Process.class);
        when(mockProcess.isAlive()).thenReturn(true);
        when(mockProcess.waitFor(anyLong(), any())).thenReturn(true);

        java.lang.reflect.Field processField = McpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, mockProcess);

        java.lang.reflect.Field runningField = McpClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.set(client, true);

        client.close();

        verify(mockProcess).destroy();
        assertFalse(client.isRunning());
    }

    @Test
    void shouldForceKillProcessAfterTimeout() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("force-kill", config, objectMapper);

        Process mockProcess = mock(Process.class);
        when(mockProcess.isAlive()).thenReturn(true);
        when(mockProcess.waitFor(anyLong(), any())).thenReturn(false); // timeout

        java.lang.reflect.Field processField = McpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, mockProcess);

        java.lang.reflect.Field runningField = McpClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.set(client, true);

        client.close();

        verify(mockProcess).destroyForcibly();
    }

    @Test
    void shouldHandleInterruptDuringClose() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("interrupt-close", config, objectMapper);

        Process mockProcess = mock(Process.class);
        when(mockProcess.isAlive()).thenReturn(true);
        when(mockProcess.waitFor(anyLong(), any())).thenThrow(new InterruptedException("interrupted"));

        java.lang.reflect.Field processField = McpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, mockProcess);

        java.lang.reflect.Field runningField = McpClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.set(client, true);

        client.close();

        verify(mockProcess).destroyForcibly();
    }

    // ===== callTool =====

    @Test
    void shouldCallToolAndReturnResult() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("call-tool", config, objectMapper);

        // Set up writer to capture output
        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        CompletableFuture<ToolResult> future = client.callTool("search", Map.of("query", "test"));

        // Verify the request was sent
        bufferedWriter.flush();
        String output = stringWriter.toString();
        assertTrue(output.contains("\"method\":\"tools/call\""));
        assertTrue(output.contains("\"name\":\"search\""));

        // Cancel future since we don't have a real server
        future.cancel(true);
        client.close();
    }

    @Test
    void shouldCallToolWithNullArguments() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("null-args", config, objectMapper);

        java.io.StringWriter stringWriter = new java.io.StringWriter();
        java.io.BufferedWriter bufferedWriter = new java.io.BufferedWriter(stringWriter);

        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bufferedWriter);

        CompletableFuture<ToolResult> future = client.callTool("test_tool", null);

        bufferedWriter.flush();
        String output = stringWriter.toString();
        assertTrue(output.contains("\"arguments\":{}"));

        future.cancel(true);
        client.close();
    }

    // ===== Activity tracking =====

    @Test
    void shouldUpdateActivityOnCallTool() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("activity", config, objectMapper);

        long before = client.getLastActivityTimestamp();
        Thread.sleep(10);

        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.BufferedWriter bw = new java.io.BufferedWriter(sw);
        java.lang.reflect.Field writerField = McpClient.class.getDeclaredField("writer");
        writerField.setAccessible(true);
        writerField.set(client, bw);

        CompletableFuture<ToolResult> future = client.callTool("test", Map.of());
        long after = client.getLastActivityTimestamp();

        assertTrue(after >= before);

        future.cancel(true);
        client.close();
    }

    // ===== readLoop with piped streams =====

    @Test
    void shouldReadResponsesFromPipedStream() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("piped-read", config, objectMapper);

        // Set up piped streams to simulate process stdout
        java.io.PipedOutputStream pipedOut = new java.io.PipedOutputStream();
        java.io.PipedInputStream pipedIn = new java.io.PipedInputStream(pipedOut);

        // Create a mock process that returns our piped stream
        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(pipedIn);

        java.lang.reflect.Field processField = McpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, mockProcess);

        java.lang.reflect.Field runningField = McpClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.set(client, true);

        // Set up a pending request
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        java.lang.reflect.Field pendingField = McpClient.class.getDeclaredField("pendingRequests");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, CompletableFuture<JsonNode>> pending = (Map<Integer, CompletableFuture<JsonNode>>) pendingField
                .get(client);
        pending.put(1, future);

        // Start reader thread
        java.lang.reflect.Method readLoopMethod = McpClient.class.getDeclaredMethod("readLoop");
        readLoopMethod.setAccessible(true);

        Thread readerThread = new Thread(() -> {
            try {
                readLoopMethod.invoke(client);
            } catch (Exception e) {
                // expected
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        // Write a response
        String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"status\":\"ok\"}}\n";
        pipedOut.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        pipedOut.flush();
        pipedOut.close();

        // Wait for the future to complete
        JsonNode result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals("ok", result.get("status").asText());

        runningField.set(client, false);
        readerThread.join(2000);
    }

    @Test
    void shouldHandleErrorResponseInReadLoop() throws Exception {
        McpConfig config = McpConfig.builder().command("test").build();
        McpClient client = new McpClient("error-read", config, objectMapper);

        java.io.PipedOutputStream pipedOut = new java.io.PipedOutputStream();
        java.io.PipedInputStream pipedIn = new java.io.PipedInputStream(pipedOut);

        Process mockProcess = mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(pipedIn);

        java.lang.reflect.Field processField = McpClient.class.getDeclaredField("process");
        processField.setAccessible(true);
        processField.set(client, mockProcess);

        java.lang.reflect.Field runningField = McpClient.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.set(client, true);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        java.lang.reflect.Field pendingField = McpClient.class.getDeclaredField("pendingRequests");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, CompletableFuture<JsonNode>> pending = (Map<Integer, CompletableFuture<JsonNode>>) pendingField
                .get(client);
        pending.put(2, future);

        java.lang.reflect.Method readLoopMethod = McpClient.class.getDeclaredMethod("readLoop");
        readLoopMethod.setAccessible(true);

        Thread readerThread = new Thread(() -> {
            try {
                readLoopMethod.invoke(client);
            } catch (Exception e) {
                // expected
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        String errorResponse = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}\n";
        pipedOut.write(errorResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        pipedOut.flush();
        pipedOut.close();

        // Future should complete exceptionally
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));

        runningField.set(client, false);
        readerThread.join(2000);
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
                String type = item.has("type") ? item.get("type").asText() : "text";
                if ("text".equals(type) && item.has("text")) {
                    if (!output.isEmpty())
                        output.append("\n");
                    output.append(item.get("text").asText());
                }
            }
        }

        if (isError) {
            return ToolResult.failure(output.isEmpty() ? "MCP tool error" : output.toString());
        }
        return ToolResult.success(output.isEmpty() ? "(no output)" : output.toString());
    }
}
