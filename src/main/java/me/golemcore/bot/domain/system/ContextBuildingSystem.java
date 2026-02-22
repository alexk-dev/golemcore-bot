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

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * System for assembling the complete LLM system prompt with memory, skills,
 * tools, and RAG context (order=20). Constructs prompt from modular sections
 * (identity, rules) via {@link service.PromptSectionService}, adds active skill
 * content or skills summary, injects memory and RAG-retrieved context, lists
 * available tools, and starts MCP servers for skills with MCP configuration.
 * Sets systemPrompt and availableTools in context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContextBuildingSystem implements AgentSystem {

    private static final String DOUBLE_NEWLINE = "\n\n";

    private final MemoryComponent memoryComponent;
    private final SkillComponent skillComponent;
    private final List<ToolComponent> toolComponents;
    private final SkillTemplateEngine templateEngine;
    private final McpPort mcpPort;
    private final ToolCallExecutionService toolCallExecutionService;
    private final RagPort ragPort;
    private final BotProperties properties;
    private final AutoModeService autoModeService;
    private final PlanService planService;
    private final PromptSectionService promptSectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService userPreferencesService;
    private final WorkspaceInstructionService workspaceInstructionService;

    @Override
    public String getName() {
        return "ContextBuildingSystem";
    }

    @Override
    public int getOrder() {
        return 20; // After sanitization
    }

    @Override
    public AgentContext process(AgentContext context) {
        log.debug("[Context] Building context...");

        // Handle skill transitions (from SkillTransitionTool or SkillPipelineSystem)
        SkillTransitionRequest transition = context.getSkillTransitionRequest();
        String transitionTarget = transition != null ? transition.targetSkill() : null;
        if (transitionTarget != null) {
            skillComponent.findByName(transitionTarget).ifPresent(skill -> {
                context.setActiveSkill(skill);
                log.info("[Context] Skill transition: → {}", skill.getName());
            });
            context.clearSkillTransitionRequest();
        }

        // Resolve model tier from user preferences and active skill
        UserPreferences prefs = userPreferencesService.getPreferences();
        resolveTier(context, prefs);

        // Build memory context from structured Memory V2 pack
        String userQueryForMemory = getLastUserMessageText(context);
        MemoryQuery memoryQuery = MemoryQuery.builder()
                .queryText(userQueryForMemory)
                .activeSkill(context.getActiveSkill() != null ? context.getActiveSkill().getName() : null)
                .softPromptBudgetTokens(runtimeConfigService.getMemorySoftPromptBudgetTokens())
                .maxPromptBudgetTokens(runtimeConfigService.getMemoryMaxPromptBudgetTokens())
                .workingTopK(runtimeConfigService.getMemoryWorkingTopK())
                .episodicTopK(runtimeConfigService.getMemoryEpisodicTopK())
                .semanticTopK(runtimeConfigService.getMemorySemanticTopK())
                .proceduralTopK(runtimeConfigService.getMemoryProceduralTopK())
                .build();

        String memoryContext = "";
        try {
            MemoryPack memoryPack = memoryComponent.buildMemoryPack(memoryQuery);
            if (memoryPack != null && memoryPack.getRenderedContext() != null
                    && !memoryPack.getRenderedContext().isBlank()) {
                memoryContext = memoryPack.getRenderedContext();
            }
            if (memoryPack != null && memoryPack.getDiagnostics() != null && !memoryPack.getDiagnostics().isEmpty()) {
                context.setAttribute(ContextAttributes.MEMORY_PACK_DIAGNOSTICS, memoryPack.getDiagnostics());
            }
        } catch (Exception e) {
            log.debug("[Context] Memory pack build failed: {}", e.getMessage());
        }
        context.setMemoryContext(memoryContext);
        log.debug("[Context] Memory context: {} chars",
                memoryContext != null ? memoryContext.length() : 0);

        // Build skills summary
        String skillsSummary = skillComponent.getSkillsSummary();
        context.setSkillsSummary(skillsSummary);
        log.debug("[Context] Skills summary: {} chars",
                skillsSummary != null ? skillsSummary.length() : 0);

        // Collect tool definitions (native + MCP)
        List<ToolDefinition> tools = new ArrayList<>(toolComponents.stream()
                .filter(ToolComponent::isEnabled)
                .filter(tool -> isToolAdvertised(tool, planService.isPlanModeActive()))
                .map(ToolComponent::getDefinition)
                .toList());

        // Start MCP server and register tools if active skill has MCP config
        if (context.getActiveSkill() != null && context.getActiveSkill().hasMcp()) {
            List<ToolDefinition> mcpTools = mcpPort.getOrStartClient(context.getActiveSkill());
            if (!mcpTools.isEmpty()) {
                for (ToolDefinition mcpTool : mcpTools) {
                    ToolComponent adapter = mcpPort.createToolAdapter(
                            context.getActiveSkill().getName(), mcpTool);
                    toolCallExecutionService.registerTool(adapter);
                    tools.add(mcpTool);
                }
                log.info("[Context] Registered {} MCP tools from skill '{}'",
                        mcpTools.size(), context.getActiveSkill().getName());
            }
        }

        context.setAvailableTools(tools);
        log.trace("[Context] Available tools: {}", tools.stream().map(ToolDefinition::getName).toList());

        // Check if skill was selected by routing
        if (context.getActiveSkill() != null) {
            log.debug("[Context] Active skill: {} ({} chars)",
                    context.getActiveSkill().getName(),
                    context.getActiveSkill().getContent() != null ? context.getActiveSkill().getContent().length() : 0);
        } else {
            log.debug("[Context] No active skill selected");
        }

        // Retrieve RAG context if available
        if (ragPort.isAvailable()) {
            String userQuery = getLastUserMessageText(context);
            if (userQuery != null && !userQuery.isBlank()) {
                try {
                    String ragContext = ragPort.query(userQuery, runtimeConfigService.getRagQueryMode()).join();
                    if (ragContext != null && !ragContext.isBlank()) {
                        context.setAttribute(ContextAttributes.RAG_CONTEXT, ragContext);
                        log.debug("[Context] RAG context: {} chars", ragContext.length());
                    }
                } catch (Exception e) {
                    log.warn("[Context] RAG query failed: {}", e.getMessage());
                }
            }
        }

        // Set model tier for auto-mode messages
        if (isAutoModeMessage(context) && context.getModelTier() == null) {
            context.setModelTier(runtimeConfigService.getAutoModelTier());
        }

        String workspaceInstructions = workspaceInstructionService.getWorkspaceInstructionsContext();
        log.debug("[Context] Workspace instructions: {} chars",
                workspaceInstructions != null ? workspaceInstructions.length() : 0);

        // Build system prompt
        String systemPrompt = buildSystemPrompt(context, prefs, workspaceInstructions);
        context.setSystemPrompt(systemPrompt);

        log.info("[Context] Built context: {} tools, memory={}, skills={}, systemPrompt={} chars",
                tools.size(),
                memoryContext != null && !memoryContext.isBlank(),
                skillsSummary != null && !skillsSummary.isBlank(),
                systemPrompt.length());

        return context;
    }

    private String buildSystemPrompt(AgentContext context, UserPreferences prefs, String workspaceInstructions) {
        StringBuilder sb = new StringBuilder();

        // 1. Render file-based prompt sections (identity, rules, etc.)
        if (promptSectionService.isEnabled()) {
            Map<String, String> vars = promptSectionService.buildTemplateVariables(prefs);
            for (PromptSection section : promptSectionService.getEnabledSections()) {
                String rendered = promptSectionService.renderSection(section, vars);
                if (rendered != null && !rendered.isBlank()) {
                    sb.append(rendered).append(DOUBLE_NEWLINE);
                }
            }
        }

        // 2. Fallback if no sections loaded
        if (sb.isEmpty()) {
            sb.append("You are a helpful AI assistant.").append(DOUBLE_NEWLINE);
        }

        if (workspaceInstructions != null && !workspaceInstructions.isBlank()) {
            sb.append("# Workspace Instructions\n");
            sb.append("Follow these repository instruction files. If instructions conflict, prefer more local files listed later.\n\n");
            sb.append(workspaceInstructions);
            sb.append(DOUBLE_NEWLINE);
        }

        if (context.getMemoryContext() != null && !context.getMemoryContext().isBlank()) {
            sb.append("# Memory\n");
            sb.append(context.getMemoryContext());
            sb.append(DOUBLE_NEWLINE);
        }

        String ragContext = context.getAttribute(ContextAttributes.RAG_CONTEXT);
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("# Relevant Memory\n");
            sb.append(ragContext);
            sb.append(DOUBLE_NEWLINE);
        }

        // If a specific skill was selected by routing, inject its full content
        if (context.getActiveSkill() != null) {
            sb.append("# Active Skill: ").append(context.getActiveSkill().getName()).append("\n");
            String skillContent = context.getActiveSkill().getContent();
            Map<String, String> vars = context.getActiveSkill().getResolvedVariables();
            if (vars != null && !vars.isEmpty()) {
                skillContent = templateEngine.render(skillContent, vars);
            }
            sb.append(skillContent);
            sb.append(DOUBLE_NEWLINE);

            // Add pipeline info if skill has transitions
            if (context.getActiveSkill().hasPipeline()) {
                sb.append("# Skill Pipeline\n");
                sb.append("You can transition to the next skill using the skill_transition tool.\n");
                if (context.getActiveSkill().getNextSkill() != null) {
                    sb.append("Default next: ").append(context.getActiveSkill().getNextSkill()).append("\n");
                }
                Map<String, String> conditional = context.getActiveSkill().getConditionalNextSkills();
                if (conditional != null && !conditional.isEmpty()) {
                    sb.append("Conditional transitions:\n");
                    for (Map.Entry<String, String> entry : conditional.entrySet()) {
                        sb.append("- ").append(entry.getKey()).append(" → ").append(entry.getValue()).append("\n");
                    }
                }
                sb.append("\n");
            }
        } else if (context.getSkillsSummary() != null && !context.getSkillsSummary().isBlank()) {
            // Otherwise show skills summary for progressive loading
            sb.append("# Available Skills\n");
            sb.append(context.getSkillsSummary());
            sb.append(DOUBLE_NEWLINE);
        }

        if (context.getAvailableTools() != null && !context.getAvailableTools().isEmpty()) {
            sb.append("# Available Tools\n");
            sb.append("You have access to the following tools:\n");
            for (ToolDefinition tool : context.getAvailableTools()) {
                sb.append("- **").append(tool.getName()).append("**: ");
                sb.append(tool.getDescription()).append("\n");
            }
            sb.append("\n");
        }

        // Tier awareness instruction (only when not forced and skill specifies tier)
        if (!prefs.isTierForce() && context.getActiveSkill() != null
                && context.getActiveSkill().getModelTier() != null) {
            sb.append("# Model Tier\n");
            sb.append("The active skill '").append(context.getActiveSkill().getName())
                    .append("' recommends the '").append(context.getActiveSkill().getModelTier())
                    .append("' model tier. The system has switched to this tier.\n\n");
        }

        // Inject auto-mode context (goals, tasks, diary)
        if (isAutoModeMessage(context)) {
            String autoContext = autoModeService.buildAutoContext();
            if (autoContext != null && !autoContext.isBlank()) {
                sb.append("\n").append(autoContext).append("\n");
            }
        }

        // Inject plan mode context and set model tier override
        if (planService.isPlanModeActive()) {
            String planContext = planService.buildPlanContext();
            if (planContext != null && !planContext.isBlank()) {
                sb.append("\n").append(planContext).append("\n");
            }
            // Override model tier if plan specifies one
            planService.getActivePlan().ifPresent(plan -> {
                if (plan.getModelTier() != null && context.getModelTier() == null) {
                    context.setModelTier(plan.getModelTier());
                }
            });
        }

        return sb.toString().trim();
    }

    private boolean isToolAdvertised(ToolComponent tool, boolean planModeActive) {
        String toolName = tool.getToolName();
        if (me.golemcore.bot.tools.PlanSetContentTool.TOOL_NAME.equals(toolName)
                || me.golemcore.bot.tools.PlanGetTool.TOOL_NAME.equals(toolName)) {
            return planModeActive;
        }
        return true;
    }

    private void resolveTier(AgentContext context, UserPreferences prefs) {
        // Only resolve on iteration 0 (DynamicTierSystem handles later iterations)
        if (context.getCurrentIteration() != 0) {
            return;
        }

        boolean force = prefs.isTierForce();
        String userTier = prefs.getModelTier();

        if (force && userTier != null) {
            context.setModelTier(userTier);
            return;
        }

        Skill activeSkill = context.getActiveSkill();
        if (activeSkill != null && activeSkill.getModelTier() != null) {
            context.setModelTier(activeSkill.getModelTier());
        } else if (userTier != null) {
            context.setModelTier(userTier);
        }
        // else: keep null. Downstream LLM execution treats null as the default tier
        // (currently "balanced") and resolves model selection accordingly.
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty())
            return false;
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }

    private String getLastUserMessageText(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage()) {
                return msg.getContent();
            }
        }
        return null;
    }
}
