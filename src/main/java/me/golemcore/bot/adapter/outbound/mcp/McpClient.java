/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.adapter.outbound.mcp;

import me.golemcore.bot.domain.model.McpConfig;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC 2.0 client for a single MCP (Model Context Protocol) server over
 * stdio.
 *
 * <p>
 * This client manages the lifecycle of an MCP server process:
 * <ol>
 * <li>Start the server process (via shell command)
 * <li>Send initialize request (JSON-RPC handshake)
 * <li>Fetch available tools (tools/list)
 * <li>Call tools (tools/call)
 * <li>Close the process
 * </ol>
 *
 * <p>
 * Communication is via JSON-RPC 2.0 over stdin/stdout. The client:
 * <ul>
 * <li>Writes JSON-RPC requests to process stdin
 * <li>Reads JSON-RPC responses from process stdout (in a reader thread)
 * <li>Drains stderr to DEBUG log (in a separate thread)
 * <li>Matches responses to requests by JSON-RPC id
 * </ul>
 *
 * <p>
 * MCP protocol version: 2024-11-05
 *
 * <p>
 * Not a Spring bean — created per skill by {@link McpClientManager}.
 *
 * @see McpClientManager
 * @see McpToolAdapter
 */
public class McpClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String JSONRPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
    };

    private final String skillName;
    private final McpConfig config;
    private final ObjectMapper objectMapper;

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;
    private Thread stderrThread;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong lastActivityTimestamp = new AtomicLong(System.currentTimeMillis());

    private volatile boolean running;
    private List<ToolDefinition> cachedTools;

    public McpClient(String skillName, McpConfig config, ObjectMapper objectMapper) {
        this.skillName = skillName;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * Start the MCP server process, send initialize, and fetch available tools.
     */
    public List<ToolDefinition> start() throws Exception {
        log.info("[MCP:{}] Starting server: {}", skillName, config.getCommand());

        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", config.getCommand());
        pb.redirectErrorStream(false);

        // Set environment variables
        Map<String, String> env = pb.environment();
        if (config.getEnv() != null) {
            env.putAll(config.getEnv());
        }

        process = pb.start();
        running = true;

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // Start reader thread for stdout (JSON-RPC responses)
        readerThread = new Thread(this::readLoop, "mcp-reader-" + skillName);
        readerThread.setDaemon(true);
        readerThread.start();

        // Start stderr drain thread
        stderrThread = new Thread(this::stderrDrain, "mcp-stderr-" + skillName);
        stderrThread.setDaemon(true);
        stderrThread.start();

        try {
            // Initialize handshake
            int timeoutSeconds = config.getStartupTimeoutSeconds();

            JsonNode initResult = sendRequest("initialize", Map.of(
                    "protocolVersion", MCP_PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of(
                            "name", "golemcore-bot",
                            "version", "1.0.0")))
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            log.info("[MCP:{}] Initialized: {}", skillName, initResult);

            // Send initialized notification
            sendNotification("notifications/initialized", Map.of());

            // Fetch tools
            JsonNode toolsResult = sendRequest("tools/list", Map.of())
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            cachedTools = parseToolDefinitions(toolsResult);
            log.info("[MCP:{}] Available tools: {}", skillName,
                    cachedTools.stream().map(ToolDefinition::getName).toList());

            touchActivity();
            return cachedTools;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[MCP:{}] Initialization failed, cleaning up: {}", skillName, e.getMessage());
            close();
            throw e;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.error("[MCP:{}] Initialization failed, cleaning up: {}", skillName, e.getMessage());
            close();
            throw e;
        }
    }

    /**
     * Call an MCP tool and return the result.
     */
    public CompletableFuture<ToolResult> callTool(String name, Map<String, Object> arguments) {
        touchActivity();
        return sendRequest("tools/call", Map.of(
                "name", name,
                "arguments", arguments != null ? arguments : Map.of()))
                .thenApply(result -> parseToolCallResult(name, result))
                .exceptionally(ex -> ToolResult.failure("MCP tool call failed: " + ex.getMessage()));
    }

    /**
     * Send a JSON-RPC request and return a future for the result.
     */
    private static final long REQUEST_TIMEOUT_SECONDS = 60;

    CompletableFuture<JsonNode> sendRequest(String method, Map<String, Object> params) {
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>()
                .orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> pendingRequests.remove(id));
        pendingRequests.put(id, future);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", JSONRPC_VERSION);
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        try {
            String json = objectMapper.writeValueAsString(request);
            log.debug("[MCP:{}] → {}", skillName, json);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Send a JSON-RPC notification (no id, no response expected).
     */
    void sendNotification(String method, Map<String, Object> params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", JSONRPC_VERSION);
        notification.put("method", method);
        if (params != null && !params.isEmpty()) {
            notification.put("params", params);
        }

        try {
            String json = objectMapper.writeValueAsString(notification);
            log.debug("[MCP:{}] → (notification) {}", skillName, json);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("[MCP:{}] Failed to send notification: {}", skillName, e.getMessage());
        }
    }

    private void readLoop() {
        Process p = this.process;
        if (p == null)
            return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty())
                    continue;
                log.debug("[MCP:{}] ← {}", skillName, line);

                try {
                    JsonNode message = objectMapper.readTree(line);

                    // Check if it's a response (has id)
                    JsonNode idNode = message.get("id");
                    if (idNode != null && idNode.isInt()) {
                        int id = idNode.asInt();
                        CompletableFuture<JsonNode> pending = pendingRequests.remove(id);
                        if (pending != null) {
                            JsonNode error = message.get("error");
                            if (error != null && !error.isNull()) {
                                pending.completeExceptionally(new McpException(
                                        error.has("code") ? error.get("code").asInt() : -1,
                                        error.has("message") ? error.get("message").asText() : "Unknown MCP error"));
                            } else {
                                pending.complete(message.get("result"));
                            }
                        } else {
                            log.warn("[MCP:{}] Received response for unknown id: {}", skillName, id);
                        }
                    } else {
                        // It's a notification from the server — log and ignore
                        String method = message.has("method") ? message.get("method").asText() : "unknown";
                        log.debug("[MCP:{}] Server notification: {}", skillName, method);
                    }
                } catch (JsonProcessingException e) {
                    log.warn("[MCP:{}] Failed to parse response: {}", skillName, e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                log.warn("[MCP:{}] Reader thread error: {}", skillName, e.getMessage());
            }
        } finally {
            // Complete any pending futures with error
            for (Map.Entry<Integer, CompletableFuture<JsonNode>> entry : pendingRequests.entrySet()) {
                entry.getValue().completeExceptionally(new IOException("MCP process closed"));
            }
            pendingRequests.clear();
        }
    }

    private void stderrDrain() {
        Process p = this.process;
        if (p == null)
            return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                log.debug("[MCP:{}] stderr: {}", skillName, line);
            }
        } catch (IOException e) {
            if (running) {
                log.debug("[MCP:{}] Stderr drain ended: {}", skillName, e.getMessage());
            }
        }
    }

    private List<ToolDefinition> parseToolDefinitions(JsonNode result) {
        if (result == null)
            return List.of();

        JsonNode toolsNode = result.get("tools");
        if (toolsNode == null || !toolsNode.isArray())
            return List.of();

        List<ToolDefinition> tools = new ArrayList<>();
        for (JsonNode toolNode : toolsNode) {
            String name = toolNode.has("name") ? toolNode.get("name").asText() : null;
            String description = toolNode.has("description") ? toolNode.get("description").asText() : "";

            if (name == null)
                continue;

            Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of());
            if (toolNode.has("inputSchema")) {
                try {
                    inputSchema = objectMapper.convertValue(toolNode.get("inputSchema"),
                            MAP_TYPE_REF);
                } catch (Exception e) {
                    log.warn("[MCP:{}] Failed to parse inputSchema for tool '{}': {}", skillName, name, e.getMessage());
                }
            }

            tools.add(ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build());
        }
        return tools;
    }

    private ToolResult parseToolCallResult(String toolName, JsonNode result) {
        if (result == null) {
            return ToolResult.failure("No result from MCP tool: " + toolName);
        }

        // Check isError flag
        boolean isError = result.has("isError") && result.get("isError").asBoolean(false);

        // Extract content
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

    private void touchActivity() {
        lastActivityTimestamp.set(System.currentTimeMillis());
    }

    public long getLastActivityTimestamp() {
        return lastActivityTimestamp.get();
    }

    public List<ToolDefinition> getCachedTools() {
        return cachedTools != null ? cachedTools : List.of();
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    public String getSkillName() {
        return skillName;
    }

    @Override
    public void close() {
        log.info("[MCP:{}] Closing client", skillName);
        running = false;

        // Complete pending requests
        for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("MCP client closing"));
        }
        pendingRequests.clear();

        // Close writer to release process stdin
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("[MCP:{}] Error closing writer: {}", skillName, e.getMessage());
            }
        }

        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * Exception for MCP JSON-RPC errors.
     */
    public static class McpException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int code;

        public McpException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
