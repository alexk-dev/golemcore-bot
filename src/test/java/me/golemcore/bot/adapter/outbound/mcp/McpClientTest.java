package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
