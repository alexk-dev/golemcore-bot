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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context for a single agent loop iteration. Contains all data needed
 * for LLM execution including session state, messages, system prompt, active
 * skills, available tools, and tool execution results. This context is passed
 * through the processing pipeline and modified by systems.
 */
@Data
@Builder
public class AgentContext {

    private AgentSession session;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    private String systemPrompt;
    private String memoryContext;
    private String skillsSummary;

    @Builder.Default
    private List<ToolDefinition> availableTools = new ArrayList<>();

    @Builder.Default
    private List<Skill> activeSkills = new ArrayList<>();

    /**
     * The skill selected by routing (if skill matcher is enabled).
     */
    private Skill activeSkill;

    /**
     * Recommended model tier from skill routing: balanced, smart, coding, deep.
     */
    private String modelTier;

    @Builder.Default
    private Map<String, ToolResult> toolResults = new HashMap<>();

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    private int currentIteration;
    private int maxIterations;

    /**
     * Adds a tool execution result to the context for correlation with tool calls.
     */
    public void addToolResult(String toolCallId, ToolResult result) {
        if (toolResults == null) {
            toolResults = new HashMap<>();
        }
        toolResults.put(toolCallId, result);
    }

    /**
     * Stores a transient attribute in the context (not persisted with session).
     */
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    /**
     * Retrieves a transient attribute from the context.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return attributes != null ? (T) attributes.get(key) : null;
    }

    // --- Typed transient attributes (preferred over string keys for control-flow
    // signals) ---

    @Builder.Default
    private SkillTransitionRequest skillTransitionRequest = null;

    @Builder.Default
    private boolean finalAnswerReady = false;

    public SkillTransitionRequest getSkillTransitionRequest() {
        return skillTransitionRequest;
    }

    public void setSkillTransitionRequest(SkillTransitionRequest request) {
        this.skillTransitionRequest = request;
    }

    public void clearSkillTransitionRequest() {
        this.skillTransitionRequest = null;
    }

    public boolean isFinalAnswerReady() {
        return finalAnswerReady;
    }

    public void setFinalAnswerReady(boolean finalAnswerReady) {
        this.finalAnswerReady = finalAnswerReady;
    }

}
