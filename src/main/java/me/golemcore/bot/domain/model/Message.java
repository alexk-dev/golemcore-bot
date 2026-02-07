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
