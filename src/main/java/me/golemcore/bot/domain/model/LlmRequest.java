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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request object sent to LLM providers containing model selection, messages,
 * system prompt, available tools, and generation parameters.
 */
@Data
@Builder
public class LlmRequest {

    private String model;
    private String systemPrompt;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @Builder.Default
    private List<ToolDefinition> tools = new ArrayList<>();

    @Builder.Default
    private Map<String, ToolResult> toolResults = new java.util.HashMap<>();

    @Builder.Default
    private double temperature = 0.7;

    private Integer maxTokens;

    @Builder.Default
    private boolean stream = false;

    private String sessionId;

    /**
     * Reasoning effort for OpenAI o-series models (o1, o3, gpt-5.1, etc.) Values:
     * "low", "medium", "high", or null to disable.
     */
    private String reasoningEffort;

    /**
     * Adds a message to the request's conversation history.
     */
    public void addMessage(Message message) {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(message);
    }

    /**
     * Adds a tool execution result to the request.
     */
    public void addToolResult(String toolCallId, ToolResult result) {
        if (toolResults == null) {
            toolResults = new java.util.HashMap<>();
        }
        toolResults.put(toolCallId, result);
    }
}
