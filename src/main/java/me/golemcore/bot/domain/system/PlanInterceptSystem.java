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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Intercepts LLM tool calls during plan mode and records them as plan steps
 * instead of executing them (order=35, between LLM at 30 and ToolExecution at
 * 40).
 *
 * <p>
 * When plan mode is active:
 * <ol>
 * <li>Reads tool calls from context</li>
 * <li>Records each as a plan step via PlanService</li>
 * <li>Adds assistant message (with tool calls) to preserve LLM reasoning</li>
 * <li>Adds synthetic "[Planned]" tool result messages</li>
 * <li>Clears llm.toolCalls to prevent ToolExecutionSystem from executing</li>
 * <li>Sets tools.executed=true to allow the loop to continue</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanInterceptSystem implements AgentSystem {

    private static final String PLANNED_RESPONSE = "[Planned — not yet executed. Continue planning or respond with plan summary.]";

    private final PlanService planService;

    @Override
    public String getName() {
        return "PlanInterceptSystem";
    }

    @Override
    public int getOrder() {
        return 35;
    }

    @Override
    public boolean isEnabled() {
        return planService.isFeatureEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (!planService.isPlanModeActive()) {
            return false;
        }
        List<Message.ToolCall> toolCalls = context.getAttribute(ContextAttributes.LLM_TOOL_CALLS);
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public AgentContext process(AgentContext context) {
        String planId = planService.getActivePlanId();
        if (planId == null) {
            log.warn("[PlanIntercept] Plan mode active but no active plan ID");
            return context;
        }

        @SuppressWarnings("unchecked")
        List<Message.ToolCall> toolCalls = context.getAttribute(ContextAttributes.LLM_TOOL_CALLS);
        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);

        log.info("[PlanIntercept] Intercepting {} tool call(s) for plan '{}'", toolCalls.size(), planId);

        // Add assistant message with tool calls to preserve LLM reasoning
        Message assistantMessage = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(llmResponse != null ? llmResponse.getContent() : null)
                .toolCalls(toolCalls)
                .timestamp(Instant.now())
                .build();
        context.getMessages().add(assistantMessage);
        context.getSession().addMessage(assistantMessage);

        // Record each tool call as a plan step and add synthetic tool results
        for (Message.ToolCall toolCall : toolCalls) {
            String description = buildStepDescription(toolCall);

            try {
                planService.addStep(planId, toolCall.getName(), toolCall.getArguments(), description);
                log.debug("[PlanIntercept] Recorded step: {} ({})", toolCall.getName(), description);
            } catch (IllegalStateException e) {
                log.warn("[PlanIntercept] Failed to add step: {}", e.getMessage());
            }

            // Add synthetic tool result message so LLM can continue planning
            Message toolMessage = Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role("tool")
                    .toolCallId(toolCall.getId())
                    .toolName(toolCall.getName())
                    .content(PLANNED_RESPONSE)
                    .timestamp(Instant.now())
                    .build();
            context.getMessages().add(toolMessage);
            context.getSession().addMessage(toolMessage);
        }

        // Clear tool calls to prevent ToolExecutionSystem from executing them
        context.setAttribute(ContextAttributes.LLM_TOOL_CALLS, null);

        // Signal that tools were "handled" so the loop continues for more planning
        context.setAttribute(ContextAttributes.TOOLS_EXECUTED, true);

        return context;
    }

    private String buildStepDescription(Message.ToolCall toolCall) {
        Map<String, Object> args = toolCall.getArguments();
        if (args == null || args.isEmpty()) {
            return toolCall.getName();
        }

        // Build a brief human-readable description from arguments
        StringBuilder sb = new StringBuilder();
        if (args.containsKey("operation")) {
            sb.append(args.get("operation"));
        }
        if (args.containsKey("command")) {
            sb.append(truncate(String.valueOf(args.get("command")), 80));
        }
        if (args.containsKey("path")) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(args.get("path"));
        }
        if (args.containsKey("url")) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(truncate(String.valueOf(args.get("url")), 80));
        }

        return sb.isEmpty() ? toolCall.getName() : sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
