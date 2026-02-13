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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.system.toolloop.DefaultToolLoopSystem;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.ToolCallExecutionResult;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.ToolConfirmationPolicy;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.ConfirmationPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Backwards-compatible system for executing tool calls requested by the LLM.
 *
 * <p>
 * Historical note: this system used to own the "tool loop" and mutate history.
 * As part of introducing ToolLoopSystem, tool execution is being refactored
 * towards a pure service ({@link ToolCallExecutionService}) that does not
 * mutate history.
 */
@Component
@Slf4j
public class ToolExecutionSystem implements AgentSystem {

    private final ToolCallExecutionService toolCallExecutionService;

    public ToolExecutionSystem(List<ToolComponent> toolComponents,
            ToolConfirmationPolicy confirmationPolicy,
            ConfirmationPort confirmationPort,
            BotProperties properties,
            List<ChannelPort> channelPorts) {
        this.toolCallExecutionService = new ToolCallExecutionService(toolComponents,
                confirmationPolicy,
                confirmationPort,
                properties,
                channelPorts);
        log.info("Registered {} tools: {}",
                toolComponents.size(),
                toolComponents.stream().map(ToolComponent::getToolName).toList());
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

        // Backwards-compatible behavior: this system still appends assistant+tool
        // messages
        // and sets tools.executed for AgentLoop continuation.

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

        for (Message.ToolCall toolCall : toolCalls) {
            ToolCallExecutionResult result = toolCallExecutionService.execute(context, toolCall);

            ToolResult toolResult = result.toolResult();
            if (toolResult == null) {
                continue;
            }

            Message toolMessage = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role("tool")
                    .toolCallId(result.toolCallId())
                    .toolName(result.toolName())
                    .content(result.toolMessageContent())
                    .timestamp(Instant.now())
                    .build();
            context.getMessages().add(toolMessage);
            context.getSession().addMessage(toolMessage);
        }

        context.setAttribute("tools.executed", true);
        return context;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // If ToolLoopSystem already completed the turn, legacy ToolExecutionSystem must
        // not run.
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.LOOP_COMPLETE))) {
            return false;
        }
        if (Boolean.TRUE.equals(context.getAttribute(DefaultToolLoopSystem.FINAL_ANSWER_READY))) {
            return false;
        }

        List<?> toolCalls = context.getAttribute("llm.toolCalls");
        return toolCalls != null && !toolCalls.isEmpty();
    }

    // --- Backwards compatibility for systems that mutate tool registry at runtime
    // ---

    public void registerTool(ToolComponent tool) {
        toolCallExecutionService.registerTool(tool);
    }

    public void unregisterTools(Collection<String> toolNames) {
        toolCallExecutionService.unregisterTools(toolNames);
    }

    public ToolComponent getTool(String name) {
        return toolCallExecutionService.getTool(name);
    }

    // --- Backwards compatibility for existing unit tests ---

    String truncateToolResult(String content, String toolName) {
        return toolCallExecutionService.truncateToolResult(content, toolName);
    }

    void extractAttachment(AgentContext context, ToolResult result, String toolName) {
        toolCallExecutionService.extractAttachment(context, result, toolName);
    }
}
