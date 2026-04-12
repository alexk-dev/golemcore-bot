package me.golemcore.bot.domain.context.layer;

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
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.port.outbound.McpPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects available tool definitions (native + MCP) and renders a tool listing
 * section in the system prompt.
 *
 * <p>
 * This layer:
 * <ul>
 * <li>Lists all native tools via {@link ToolCallExecutionService}</li>
 * <li>Filters by enabled/advertised status (plan mode, Hive, delays)</li>
 * <li>Starts MCP servers for active skills with MCP config</li>
 * <li>Creates turn-scoped tool adapters for MCP tools</li>
 * <li>Sets {@code availableTools} and {@code CONTEXT_SCOPED_TOOLS} on
 * context</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
public class ToolLayer implements ContextLayer {

    private static final String TOOL_PLAN_SET_CONTENT = "plan_set_content";
    private static final String TOOL_PLAN_GET = "plan_get";
    private static final String TOOL_HIVE_LIFECYCLE_SIGNAL = ToolNames.HIVE_LIFECYCLE_SIGNAL;

    private final ToolCallExecutionService toolCallExecutionService;
    private final McpPort mcpPort;
    private final PlanService planService;
    private final DelayedActionPolicyService delayedActionPolicyService;

    @Override
    public String getName() {
        return "tool";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return true;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(
                context.getSession());
        boolean planModeActive = sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
        boolean hiveSessionActive = isHiveSession(context);

        // Collect native tools
        Map<String, ToolDefinition> toolsByName = new LinkedHashMap<>();
        toolCallExecutionService.listTools().stream()
                .filter(ToolComponent::isEnabled)
                .filter(tool -> isToolAdvertised(tool, context, planModeActive, hiveSessionActive))
                .map(ToolComponent::getDefinition)
                .forEach(tool -> putToolDefinition(toolsByName, tool, false));

        // Collect MCP tools for active skill
        Map<String, ToolComponent> contextScopedTools = new LinkedHashMap<>();
        if (context.getActiveSkill() != null && context.getActiveSkill().hasMcp()) {
            List<ToolDefinition> mcpTools = mcpPort.getOrStartClient(context.getActiveSkill());
            if (!mcpTools.isEmpty()) {
                for (ToolDefinition mcpTool : mcpTools) {
                    if (mcpTool == null || mcpTool.getName() == null || mcpTool.getName().isBlank()) {
                        continue;
                    }
                    ToolComponent adapter = mcpPort.createToolAdapter(
                            context.getActiveSkill().getName(), mcpTool);
                    if (adapter == null) {
                        log.warn("[ToolLayer] Skipping MCP tool '{}' — adapter creation returned null",
                                mcpTool.getName());
                        continue;
                    }
                    contextScopedTools.put(mcpTool.getName(), adapter);
                    putToolDefinition(toolsByName, mcpTool, true);
                }
                log.info("[ToolLayer] Loaded {} MCP tools for skill '{}'",
                        mcpTools.size(), context.getActiveSkill().getName());
            }
        }

        context.setAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS,
                contextScopedTools.isEmpty() ? null : contextScopedTools);
        List<ToolDefinition> tools = new ArrayList<>(toolsByName.values());
        context.setAvailableTools(tools);

        if (tools.isEmpty()) {
            return ContextLayerResult.empty(getName());
        }

        StringBuilder sb = new StringBuilder("# Available Tools\nYou have access to the following tools:\n");
        for (ToolDefinition tool : tools) {
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(tool.getDescription()).append("\n");
        }

        String content = sb.toString();
        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }

    private boolean isToolAdvertised(ToolComponent tool, AgentContext context,
            boolean planModeActive, boolean hiveSessionActive) {
        String toolName = tool.getToolName();
        if (TOOL_PLAN_SET_CONTENT.equals(toolName) || TOOL_PLAN_GET.equals(toolName)) {
            return planModeActive;
        }
        if (ToolNames.SCHEDULE_SESSION_ACTION.equals(toolName)) {
            String channelType = context != null && context.getSession() != null
                    ? context.getSession().getChannelType()
                    : null;
            return delayedActionPolicyService.canScheduleActions(channelType);
        }
        if (TOOL_HIVE_LIFECYCLE_SIGNAL.equals(toolName)) {
            return hiveSessionActive;
        }
        return true;
    }

    private void putToolDefinition(Map<String, ToolDefinition> toolsByName,
            ToolDefinition tool, boolean replaceExisting) {
        if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
            return;
        }
        if (!replaceExisting && toolsByName.containsKey(tool.getName())) {
            return;
        }
        ToolDefinition previous = toolsByName.put(tool.getName(), tool);
        if (replaceExisting && previous != null) {
            log.warn("[ToolLayer] Replaced tool '{}' with MCP tool for current turn", tool.getName());
        }
    }

    private boolean isHiveSession(AgentContext context) {
        return context != null
                && context.getSession() != null
                && "hive".equalsIgnoreCase(context.getSession().getChannelType());
    }
}
