package me.golemcore.bot.domain.model;

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

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single message in a conversation between user and assistant.
 * Supports multiple roles (user, assistant, system, tool) and includes
 * metadata, tool calls, and voice message capabilities.
 */
@Data
@Builder
public class Message {

    private String id;
    private String role; // user, assistant, system, tool
    private String content;
    private String channelType;
    private String chatId;
    private String senderId;

    private List<ToolCall> toolCalls;
    private String toolCallId; // For tool response messages
    private String toolName; // Tool name for tool response messages

    private Map<String, Object> metadata;
    private Instant timestamp;

    // Voice message support
    private byte[] voiceData;
    private String voiceTranscription;
    private AudioFormat audioFormat;

    /**
     * Checks if this message is from the user.
     */
    public boolean isUserMessage() {
        return "user".equals(role);
    }

    /**
     * Checks if this message is from the assistant.
     */
    public boolean isAssistantMessage() {
        return "assistant".equals(role);
    }

    /**
     * Checks if this is a system message.
     */
    public boolean isSystemMessage() {
        return "system".equals(role);
    }

    /**
     * Checks if this is a tool result message.
     */
    public boolean isToolMessage() {
        return "tool".equals(role);
    }

    /**
     * Checks if this message contains tool calls from the LLM.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Checks if this message contains voice data.
     */
    public boolean hasVoice() {
        return voiceData != null && voiceData.length > 0;
    }

    private static final int MAX_ARGS_LENGTH = 200;
    private static final int MAX_RESULT_LENGTH = 2000;

    /**
     * Converts tool call interactions in a message list to plain text. Assistant
     * messages with tool calls and their corresponding tool result messages are
     * replaced with a single assistant message describing the tool invocations and
     * results in human-readable format.
     *
     * <p>
     * This is used when switching LLM models mid-conversation or during compaction,
     * to avoid sending provider-specific tool call metadata to a different
     * provider.
     *
     * <p>
     * The method is idempotent: messages without tool calls pass through unchanged.
     *
     * @param messages
     *            the message list to flatten
     * @return a new list with tool interactions converted to plain text
     */
    public static List<Message> flattenToolMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // Collect tool results indexed by toolCallId for lookup
        Map<String, Message> toolResultsByCallId = new LinkedHashMap<>();
        for (Message msg : messages) {
            if (msg.isToolMessage() && msg.getToolCallId() != null) {
                toolResultsByCallId.put(msg.getToolCallId(), msg);
            }
        }

        // If no tool messages exist, return as-is
        if (toolResultsByCallId.isEmpty() && messages.stream().noneMatch(Message::hasToolCalls)) {
            return messages;
        }

        List<Message> result = new ArrayList<>();
        // Track which tool result messages have been consumed
        java.util.Set<String> consumedToolCallIds = new java.util.HashSet<>();

        for (Message msg : messages) {
            if (msg.isToolMessage()) {
                // Check if this tool message was consumed by a preceding assistant message
                if (consumedToolCallIds.contains(msg.getToolCallId())) {
                    continue; // already folded into the assistant message
                }
                // Orphaned tool message — convert to assistant text
                result.add(Message.builder()
                        .id(msg.getId())
                        .role("assistant")
                        .content(formatOrphanedToolResult(msg))
                        .timestamp(msg.getTimestamp())
                        .metadata(msg.getMetadata())
                        .build());
            } else if (msg.isAssistantMessage() && msg.hasToolCalls()) {
                // Flatten tool calls into text
                StringBuilder sb = new StringBuilder();
                if (msg.getContent() != null && !msg.getContent().isBlank()) {
                    sb.append(msg.getContent()).append("\n");
                }
                for (ToolCall tc : msg.getToolCalls()) {
                    sb.append("\n[Tool: ").append(tc.getName());
                    sb.append(" | Args: ").append(truncateStr(formatArgs(tc.getArguments()), MAX_ARGS_LENGTH));
                    sb.append("]\n");

                    Message toolResult = tc.getId() != null ? toolResultsByCallId.get(tc.getId()) : null;
                    if (toolResult != null) {
                        consumedToolCallIds.add(tc.getId());
                        String resultContent = toolResult.getContent();
                        if (resultContent == null || resultContent.isEmpty()) {
                            sb.append("[Result: <empty>]\n");
                        } else {
                            sb.append("[Result: ").append(truncateStr(resultContent, MAX_RESULT_LENGTH)).append("]\n");
                        }
                    } else {
                        sb.append("[Result: <no response>]\n");
                    }
                }

                result.add(Message.builder()
                        .id(msg.getId())
                        .role("assistant")
                        .content(sb.toString().stripTrailing())
                        .timestamp(msg.getTimestamp())
                        .metadata(msg.getMetadata())
                        .build());
            } else {
                result.add(msg);
            }
        }

        return result;
    }

    private static String formatArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("\"").append(entry.getKey()).append("\": ");
            Object val = entry.getValue();
            if (val instanceof String) {
                sb.append("\"").append(val).append("\"");
            } else {
                sb.append(val);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String truncateStr(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private static String formatOrphanedToolResult(Message toolMsg) {
        String toolName = toolMsg.getToolName() != null ? toolMsg.getToolName() : "unknown";
        String content = toolMsg.getContent();
        if (content == null || content.isEmpty()) {
            content = "<empty>";
        } else {
            content = truncateStr(content, MAX_RESULT_LENGTH);
        }
        return "[Tool: " + toolName + "]\n[Result: " + content + "]";
    }

    /**
     * Represents a function call requested by the LLM. Contains the tool name, ID
     * for correlation, and JSON arguments.
     */
    @Data
    @Builder
    public static class ToolCall {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }
}
