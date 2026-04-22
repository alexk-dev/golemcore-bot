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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.context.LayerCriticality;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.MemoryPresetIds;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.port.outbound.McpPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects available tool definitions (native + MCP) and renders a tool listing
 * section in the system prompt.
 *
 * <p>
 * This layer:
 * <ul>
 * <li>Lists all native tools via {@link ToolCallExecutionService}</li>
 * <li>Filters by enabled/advertised status (Hive, delays)</li>
 * <li>Starts MCP servers for active skills with MCP config</li>
 * <li>Creates turn-scoped tool adapters for MCP tools</li>
 * <li>Sets {@code availableTools} and {@code CONTEXT_SCOPED_TOOLS} on
 * context</li>
 * </ul>
 */
@Slf4j
public class ToolLayer extends AbstractContextLayer {

    private static final int MAX_CATALOG_TOOLS = 24;
    private static final int MAX_DESCRIPTION_CHARS = 180;
    private static final String TOOL_USE_POLICY = """
            # Tool Use Policy
            - Use tools only when they are needed for the current task.
            - Use tool schemas as the source of truth for arguments.
            - If a tool fails, adjust once instead of repeating the same call.
            - Respect shell, file, and network safety constraints.

            ## Available Tools Catalog
            """;
    private static final String SHELL_TOOL_POLICY = """

            ## Shell Tool Policy

            Prefer `command -v` before using shell tools. Avoid issuing the same missing command twice without proving the binary is available.
            """;
    private static final String PLAN_MODE_TOOL_POLICY = """

            ## Plan Mode Tool Restrictions

            Plan Mode is active. Shell tools and mutating edit/write/patch tools are unavailable. Use only the advertised plan-mode tools and their schemas.
            """;

    private final ToolCallExecutionService toolCallExecutionService;
    private final McpPort mcpPort;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final PlanModeToolRestrictionService planModeToolRestrictionService;

    public ToolLayer(ToolCallExecutionService toolCallExecutionService,
            McpPort mcpPort,
            DelayedActionPolicyService delayedActionPolicyService) {
        this(toolCallExecutionService, mcpPort, delayedActionPolicyService, null);
    }

    public ToolLayer(ToolCallExecutionService toolCallExecutionService,
            McpPort mcpPort,
            DelayedActionPolicyService delayedActionPolicyService,
            PlanModeToolRestrictionService planModeToolRestrictionService) {
        super("tool", 50, 65, ContextLayerLifecycle.TURN, 3_000, true);
        this.toolCallExecutionService = toolCallExecutionService;
        this.mcpPort = mcpPort;
        this.delayedActionPolicyService = delayedActionPolicyService;
        this.planModeToolRestrictionService = planModeToolRestrictionService;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return true;
    }

    @Override
    public LayerCriticality getCriticality() {
        return LayerCriticality.REQUIRED_COMPRESSIBLE;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        boolean hiveSessionActive = isHiveSession(context);

        // Collect native tools
        Map<String, ToolDefinition> toolsByName = new LinkedHashMap<>();
        toolCallExecutionService.listTools().stream()
                .filter(ToolComponent::isEnabled)
                .filter(tool -> isToolAdvertised(tool, context, hiveSessionActive))
                .map(ToolComponent::getDefinition)
                .map(tool -> restrictToolDefinition(context, tool))
                .forEach(tool -> putToolDefinition(toolsByName, tool, false));

        // Collect MCP tools for active skill
        Map<String, ToolComponent> contextScopedTools = new LinkedHashMap<>();
        if (context.getActiveSkill() != null && context.getActiveSkill().hasMcp()) {
            List<ToolDefinition> mcpTools = mcpPort.getOrStartClient(context.getActiveSkill());
            if (!mcpTools.isEmpty()) {
                for (ToolDefinition mcpTool : mcpTools) {
                    if (mcpTool == null || mcpTool.getName() == null || mcpTool.getName().isBlank()
                            || !isMcpToolAdvertised(context, mcpTool.getName())) {
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
                    putToolDefinition(toolsByName, restrictToolDefinition(context, mcpTool), true);
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
            return empty();
        }

        StringBuilder sb = new StringBuilder(TOOL_USE_POLICY);
        int renderedTools = 0;
        for (ToolDefinition tool : tools) {
            if (renderedTools >= MAX_CATALOG_TOOLS) {
                break;
            }
            sb.append("- **").append(tool.getName()).append("**: ");
            sb.append(compactDescription(tool.getDescription())).append("\n");
            renderedTools++;
        }
        if (tools.size() > renderedTools) {
            sb.append("- ").append(tools.size() - renderedTools)
                    .append(" additional tools are available through schemas.\n");
        }
        if (toolsByName.containsKey(ToolNames.SHELL)) {
            sb.append(SHELL_TOOL_POLICY);
        }
        if (isPlanModeActive(context)) {
            sb.append(PLAN_MODE_TOOL_POLICY);
        }

        String content = sb.toString();
        return result(content);
    }

    private String compactDescription(String description) {
        if (description == null || description.isBlank()) {
            return "No description provided.";
        }
        String normalized = description.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_DESCRIPTION_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_DESCRIPTION_CHARS) + "...";
    }

    private boolean isToolAdvertised(ToolComponent tool, AgentContext context, boolean hiveSessionActive) {
        String toolName = tool.getToolName();
        if (ToolNames.SCHEDULE_SESSION_ACTION.equals(toolName)) {
            String channelType = context != null && context.getSession() != null
                    ? context.getSession().getChannelType()
                    : null;
            return delayedActionPolicyService.canScheduleActions(channelType);
        }
        if (ToolNames.MEMORY.equals(toolName) && isMemoryPresetDisabled(context)) {
            return false;
        }
        if (isHiveSdlcTool(toolName)) {
            return hiveSessionActive;
        }
        return isToolNameAdvertised(context, toolName);
    }

    private boolean isToolNameAdvertised(AgentContext context, String toolName) {
        if (planModeToolRestrictionService == null) {
            return true;
        }
        return planModeToolRestrictionService.shouldAdvertiseTool(context, toolName);
    }

    private boolean isMcpToolAdvertised(AgentContext context, String toolName) {
        if (isPlanModeActive(context) && isPrivilegedPlanModeNativeToolName(toolName)) {
            return false;
        }
        return isToolNameAdvertised(context, toolName);
    }

    private boolean isPrivilegedPlanModeNativeToolName(String toolName) {
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        return ToolNames.FILESYSTEM.equals(normalized)
                || ToolNames.GOAL_MANAGEMENT.equals(normalized)
                || ToolNames.PLAN_EXIT.equals(normalized);
    }

    private boolean isPlanModeActive(AgentContext context) {
        return planModeToolRestrictionService != null
                && planModeToolRestrictionService.isPlanModeActive(context);
    }

    private ToolDefinition restrictToolDefinition(AgentContext context, ToolDefinition toolDefinition) {
        if (planModeToolRestrictionService == null) {
            return toolDefinition;
        }
        return planModeToolRestrictionService.restrictToolDefinition(context, toolDefinition);
    }

    private boolean isMemoryPresetDisabled(AgentContext context) {
        String memoryPreset = context != null ? context.getAttribute(ContextAttributes.MEMORY_PRESET_ID) : null;
        return memoryPreset != null && MemoryPresetIds.DISABLED.equalsIgnoreCase(memoryPreset.trim());
    }

    private boolean isHiveSdlcTool(String toolName) {
        return toolName != null && ToolNames.HIVE_SDLC_TOOLS.contains(toolName);
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
                && ChannelTypes.HIVE.equalsIgnoreCase(context.getSession().getChannelType());
    }
}
