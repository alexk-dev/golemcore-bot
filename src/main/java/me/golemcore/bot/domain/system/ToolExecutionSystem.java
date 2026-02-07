package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ToolConfirmationPolicy;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * System for executing tool calls requested by the LLM and managing the tool
 * execution loop (order=40). Maintains tool registry, handles confirmation for
 * destructive operations via {@link port.outbound.ConfirmationPort}, executes
 * tools in parallel with timeout, truncates large results, extracts attachments
 * (screenshots, file_bytes), adds assistant+tool messages to conversation
 * history, and loops back to LLM if more tool calls are needed. Sets
 * {@link loop.AgentContextHolder} for tools requiring access to agent context.
 */
@Component
@Slf4j
public class ToolExecutionSystem implements AgentSystem {

    private final Map<String, ToolComponent> toolRegistry = new ConcurrentHashMap<>();
    private final ToolConfirmationPolicy confirmationPolicy;
    private final ConfirmationPort confirmationPort;
    private final BotProperties properties;
    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();

    private static final long TOOL_TIMEOUT_SECONDS = 30;

    public ToolExecutionSystem(List<ToolComponent> toolComponents,
            ToolConfirmationPolicy confirmationPolicy,
            ConfirmationPort confirmationPort,
            BotProperties properties,
            List<ChannelPort> channelPorts) {
        this.confirmationPolicy = confirmationPolicy;
        this.confirmationPort = confirmationPort;
        this.properties = properties;
        for (ToolComponent tool : toolComponents) {
            toolRegistry.put(tool.getToolName(), tool);
        }
        for (ChannelPort port : channelPorts) {
            channelRegistry.put(port.getChannelType(), port);
        }
        log.info("Registered {} tools: {}", toolRegistry.size(), toolRegistry.keySet());
    }

    @Override
    public String getName() {
        return "ToolExecutionSystem";
    }

    @Override
    public int getOrder() {
        return 40; // After LLM execution
    }

    @Override
    public AgentContext process(AgentContext context) {
        @SuppressWarnings("unchecked")
        List<Message.ToolCall> toolCalls = context.getAttribute("llm.toolCalls");

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.debug("[Tools] No tool calls to execute");
            return context;
        }

        log.info("[Tools] Executing {} tool call(s)", toolCalls.size());

        // Normalize tool call IDs to max 40 chars (OpenAI limit).
        // Some providers generate longer IDs; we remap at source so history is always
        // valid.
        toolCalls = normalizeToolCallIds(toolCalls);
        context.setAttribute("llm.toolCalls", toolCalls);

        // Set context for tools that need it (e.g. SkillTransitionTool)
        AgentContextHolder.set(context);

        // Add assistant message with tool calls to history
        me.golemcore.bot.domain.model.LlmResponse llmResponse = context.getAttribute("llm.response");
        Message assistantMessage = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(llmResponse != null ? llmResponse.getContent() : null)
                .toolCalls(toolCalls)
                .timestamp(Instant.now())
                .build();
        context.getMessages().add(assistantMessage);
        context.getSession().addMessage(assistantMessage);

        try {
            for (Message.ToolCall toolCall : toolCalls) {
                log.debug("[Tools] Calling '{}' (id: {})", toolCall.getName(), toolCall.getId());
                log.trace("[Tools] Arguments: {}", toolCall.getArguments());

                // Check if this tool call requires user confirmation
                if (confirmationPolicy.requiresConfirmation(toolCall) && confirmationPort.isAvailable()) {
                    String chatId = context.getSession().getChatId();
                    String description = confirmationPolicy.describeAction(toolCall);

                    log.info("[Tools] Requesting confirmation for '{}': {}", toolCall.getName(), description);

                    boolean approved;
                    try {
                        approved = confirmationPort.requestConfirmation(chatId, toolCall.getName(), description).join();
                    } catch (Exception e) {
                        log.error("[Tools] Confirmation request failed, denying", e);
                        approved = false;
                    }

                    if (!approved) {
                        log.info("[Tools] Tool call '{}' denied by user", toolCall.getName());
                        ToolResult denied = ToolResult.failure("Cancelled by user");
                        context.addToolResult(toolCall.getId(), denied);

                        Message toolMessage = Message.builder()
                                .id(UUID.randomUUID().toString())
                                .role("tool")
                                .toolCallId(toolCall.getId())
                                .toolName(toolCall.getName())
                                .content("Error: Cancelled by user")
                                .timestamp(Instant.now())
                                .build();
                        context.getMessages().add(toolMessage);
                        context.getSession().addMessage(toolMessage);
                        continue;
                    }

                    log.info("[Tools] Tool call '{}' confirmed by user", toolCall.getName());
                } else if (!confirmationPolicy.isEnabled() && confirmationPolicy.isNotableAction(toolCall)) {
                    // Confirmation disabled — send informational notification (non-blocking)
                    notifyToolExecution(context, toolCall);
                }

                long startMs = System.currentTimeMillis();
                ToolResult result = executeToolCall(toolCall);
                context.addToolResult(toolCall.getId(), result);
                extractAttachment(context, result, toolCall.getName());

                log.debug("[Tools] '{}' completed in {}ms, success={}",
                        toolCall.getName(), System.currentTimeMillis() - startMs, result.isSuccess());
                if (result.isSuccess()) {
                    log.trace("[Tools] Result: {}", truncate(result.getOutput(), 200));
                } else {
                    log.warn("[Tools] Error: {}", result.getError());
                    if (result.getOutput() != null) {
                        log.warn("[Tools] Output: {}", truncate(result.getOutput(), 500));
                    }
                }

                // Add tool result message to history
                // Include output even on failure (e.g., shell command stderr)
                String content;
                if (result.isSuccess()) {
                    content = result.getOutput();
                } else if (result.getOutput() != null && !result.getOutput().isBlank()) {
                    content = result.getOutput(); // Output includes error details (e.g., exit code + stderr)
                } else {
                    content = "Error: " + result.getError();
                }
                content = truncateToolResult(content, toolCall.getName());
                Message toolMessage = Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role("tool")
                        .toolCallId(toolCall.getId())
                        .toolName(toolCall.getName())
                        .content(content)
                        .timestamp(Instant.now())
                        .build();
                context.getMessages().add(toolMessage);
                context.getSession().addMessage(toolMessage);
            }

            // Mark that tools were executed (for loop continuation)
            context.setAttribute("tools.executed", true);
        } finally {
            AgentContextHolder.clear();
        }

        return context;
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * Truncate tool result content that exceeds the configured max length. Prevents
     * huge API responses from blowing up the LLM context window.
     */
    /**
     * Truncate tool result content that exceeds the configured max length. Prevents
     * huge API responses from blowing up the LLM context window.
     */
    String truncateToolResult(String content, String toolName) {
        if (content == null)
            return null;
        int maxChars = properties.getAutoCompact().getMaxToolResultChars();
        if (maxChars <= 0 || content.length() <= maxChars)
            return content;

        String suffix = "\n\n[OUTPUT TRUNCATED: " + content.length() + " chars total, showing first "
                + maxChars + " chars. The full result is too large for the context window."
                + " Try a more specific query, use filtering/pagination, or process the data in smaller chunks.]";
        int cutPoint = Math.max(0, maxChars - suffix.length());
        log.warn("[Tools] Truncating '{}' result: {} chars -> ~{} chars",
                toolName, content.length(), cutPoint + suffix.length());
        return content.substring(0, cutPoint) + suffix;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        List<?> toolCalls = context.getAttribute("llm.toolCalls");
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private ToolResult executeToolCall(Message.ToolCall toolCall) {
        String toolName = sanitizeToolName(toolCall.getName());
        ToolComponent tool = toolRegistry.get(toolName);

        if (tool == null) {
            String available = String.join(", ", toolRegistry.keySet());
            return ToolResult.failure("Unknown tool: " + toolName + ". Available tools: " + available);
        }

        if (!tool.isEnabled()) {
            return ToolResult.failure("Tool is disabled: " + toolCall.getName());
        }

        try {
            CompletableFuture<ToolResult> future = tool.execute(toolCall.getArguments());
            return future.get(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return ToolResult.failure("Tool execution failed: " + e.getMessage());
        }
    }

    public void registerTool(ToolComponent tool) {
        toolRegistry.put(tool.getToolName(), tool);
    }

    public void unregisterTools(Collection<String> toolNames) {
        if (toolNames == null)
            return;
        for (String name : toolNames) {
            toolRegistry.remove(name);
        }
        log.debug("Unregistered tools: {}", toolNames);
    }

    public ToolComponent getTool(String name) {
        return toolRegistry.get(name);
    }

    /**
     * Strip special tokens and garbage from tool names. Some models (e.g. gpt-oss)
     * leak special tokens like {@code <|channel|>} into tool call names, producing
     * e.g. {@code "filesystem<|channel|>commentary"}.
     */
    private String sanitizeToolName(String name) {
        if (name == null)
            return null;
        // Strip everything from the first '<' or non-tool-name character
        String sanitized = name.replaceAll("[^a-zA-Z0-9_-].*", "");
        if (!sanitized.equals(name)) {
            log.warn("[Tools] Sanitized tool name: '{}' -> '{}'", name, sanitized);
        }
        return sanitized;
    }

    private static final int MAX_TOOL_CALL_ID_LENGTH = 40;

    private List<Message.ToolCall> normalizeToolCallIds(List<Message.ToolCall> toolCalls) {
        boolean needsNormalization = toolCalls.stream()
                .anyMatch(tc -> tc.getId() != null && tc.getId().length() > MAX_TOOL_CALL_ID_LENGTH);
        if (!needsNormalization)
            return toolCalls;

        return toolCalls.stream()
                .map(tc -> {
                    if (tc.getId() == null || tc.getId().length() <= MAX_TOOL_CALL_ID_LENGTH)
                        return tc;
                    String shortId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    log.debug("[Tools] Normalized tool call ID: {} -> {}", tc.getId(), shortId);
                    return Message.ToolCall.builder()
                            .id(shortId)
                            .name(tc.getName())
                            .arguments(tc.getArguments())
                            .build();
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    void extractAttachment(AgentContext context, ToolResult result, String toolName) {
        if (result == null || !result.isSuccess() || !(result.getData() instanceof Map<?, ?> dataMap)) {
            return;
        }

        Attachment attachment = null;

        // Case 1: explicit Attachment object
        Object attachmentObj = dataMap.get("attachment");
        if (attachmentObj instanceof Attachment a) {
            attachment = a;
        }

        // Case 2: screenshot_base64
        if (attachment == null) {
            Object screenshotB64 = dataMap.get("screenshot_base64");
            if (screenshotB64 instanceof String b64) {
                // Guard against OOM: reject base64 strings > ~50MB decoded
                if (b64.length() > 67_000_000) { // ~50MB when decoded (base64 is ~4/3 ratio)
                    log.warn("[Tools] Base64 data too large ({} chars) from '{}', skipping", b64.length(), toolName);
                } else {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(b64);
                        attachment = Attachment.builder()
                                .type(Attachment.Type.IMAGE)
                                .data(bytes)
                                .filename("screenshot.png")
                                .mimeType("image/png")
                                .build();
                    } catch (IllegalArgumentException e) {
                        log.warn("[Tools] Invalid base64 in screenshot from '{}'", toolName);
                    }
                }
            }
        }

        // Case 3: file_bytes with metadata
        if (attachment == null) {
            Object fileBytes = dataMap.get("file_bytes");
            if (fileBytes instanceof byte[] bytes) {
                String filename = dataMap.containsKey("filename") ? dataMap.get("filename").toString() : "file";
                String mimeType = dataMap.containsKey("mime_type") ? dataMap.get("mime_type").toString()
                        : "application/octet-stream";
                Attachment.Type type = mimeType.startsWith("image/") ? Attachment.Type.IMAGE : Attachment.Type.DOCUMENT;
                attachment = Attachment.builder()
                        .type(type)
                        .data(bytes)
                        .filename(filename)
                        .mimeType(mimeType)
                        .build();
            }
        }

        if (attachment != null) {
            List<Attachment> pending = context.getAttribute("pendingAttachments");
            if (pending == null) {
                pending = new ArrayList<>();
                context.setAttribute("pendingAttachments", pending);
            }
            pending.add(attachment);
            log.debug("[Tools] Queued attachment: {} ({}, {} bytes)",
                    attachment.getFilename(), attachment.getType(), attachment.getData().length);
        }
    }

    private void notifyToolExecution(AgentContext context, Message.ToolCall toolCall) {
        try {
            String channelType = context.getSession().getChannelType();
            String chatId = context.getSession().getChatId();
            ChannelPort channel = channelRegistry.get(channelType);
            if (channel == null)
                return;

            String description = confirmationPolicy.describeAction(toolCall);
            String notification = "\u2699\ufe0f " + description;
            channel.sendMessage(chatId, notification);
            log.debug("[Tools] Notified user about '{}': {}", toolCall.getName(), description);
        } catch (Exception e) {
            log.debug("[Tools] Failed to send tool notification: {}", e.getMessage());
        }
    }
}
