package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Estimates LLM request size for compaction preflight decisions.
 *
 * <p>
 * This intentionally remains provider-agnostic: exact tokenization depends on
 * the vendor/model and request serialization, so this helper uses a
 * conservative character-based estimate plus per-message/tool overhead. The
 * estimate is meant for safety gating, not billing.
 * </p>
 */
public class ContextTokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5d;
    private static final int MESSAGE_OVERHEAD_TOKENS = 12;
    private static final int TOOL_DEFINITION_OVERHEAD_TOKENS = 48;
    private static final int TOOL_RESULT_OVERHEAD_TOKENS = 16;
    private static final int REQUEST_BASE_OVERHEAD_TOKENS = 256;

    public int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        for (Message message : messages) {
            tokens += estimateMessage(message);
        }
        return saturatingToInt(tokens);
    }

    public int estimateRequest(LlmRequest request) {
        if (request == null) {
            return 0;
        }
        long tokens = REQUEST_BASE_OVERHEAD_TOKENS;
        tokens += estimateText(request.getSystemPrompt());
        tokens += estimateMessages(request.getMessages());
        tokens += estimateTools(request.getTools());
        tokens += estimateToolResults(request.getToolResults());
        return saturatingToInt(tokens);
    }

    private int estimateMessage(Message message) {
        if (message == null) {
            return 0;
        }
        long tokens = MESSAGE_OVERHEAD_TOKENS;
        tokens += estimateText(message.getRole());
        tokens += estimateText(message.getContent());
        tokens += estimateText(message.getToolCallId());
        tokens += estimateText(message.getToolName());
        tokens += estimateToolCalls(message.getToolCalls());
        tokens += estimateMetadata(message.getMetadata());
        return saturatingToInt(tokens);
    }

    private int estimateToolCalls(List<Message.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        for (Message.ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            tokens += estimateText(toolCall.getId());
            tokens += estimateText(toolCall.getName());
            tokens += estimateObjectMap(toolCall.getArguments());
        }
        return saturatingToInt(tokens);
    }

    private int estimateTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        for (ToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            tokens += TOOL_DEFINITION_OVERHEAD_TOKENS;
            tokens += estimateText(tool.getName());
            tokens += estimateText(tool.getDescription());
            tokens += estimateObjectMap(tool.getInputSchema());
        }
        return saturatingToInt(tokens);
    }

    private int estimateToolResults(Map<String, ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        for (Map.Entry<String, ToolResult> entry : toolResults.entrySet()) {
            tokens += TOOL_RESULT_OVERHEAD_TOKENS;
            tokens += estimateText(entry.getKey());
            ToolResult toolResult = entry.getValue();
            if (toolResult == null) {
                continue;
            }
            tokens += estimateText(toolResult.getOutput());
            tokens += estimateText(toolResult.getError());
            tokens += estimateObject(toolResult.getData());
        }
        return saturatingToInt(tokens);
    }

    private int estimateMetadata(Map<String, Object> metadata) {
        return estimateObjectMap(metadata);
    }

    private int estimateObjectMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        long tokens = 2;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            tokens += estimateText(entry.getKey());
            tokens += estimateObject(entry.getValue());
        }
        return saturatingToInt(tokens);
    }

    private int estimateObject(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String stringValue) {
            return estimateText(stringValue);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return estimateText(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> mapValue) {
            return estimateRawMap(mapValue);
        }
        if (value instanceof Iterable<?> iterableValue) {
            return estimateIterable(iterableValue);
        }
        if (value.getClass().isArray()) {
            return estimateText(String.valueOf(value));
        }
        return estimateText(String.valueOf(value));
    }

    private int estimateRawMap(Map<?, ?> values) {
        long tokens = 2;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            tokens += estimateObject(entry.getKey());
            tokens += estimateObject(entry.getValue());
        }
        return saturatingToInt(tokens);
    }

    private int estimateIterable(Iterable<?> values) {
        long tokens = 2;
        for (Object value : values) {
            tokens += estimateObject(value);
        }
        return saturatingToInt(tokens);
    }

    private int estimateText(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(value.length() / CHARS_PER_TOKEN));
    }

    private int saturatingToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < 0) {
            return 0;
        }
        return (int) value;
    }
}
