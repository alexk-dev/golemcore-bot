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
import me.golemcore.bot.domain.model.MemoryPack;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.MemoryScopeSupport;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.bot.tools.ScheduleSessionActionTool;

import me.golemcore.bot.tools.HiveLifecycleSignalTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private static final String TOOL_PLAN_SET_CONTENT = "plan_set_content";
    private static final String TOOL_PLAN_GET = "plan_get";
    private static final String TOOL_HIVE_LIFECYCLE_SIGNAL = HiveLifecycleSignalTool.TOOL_NAME;

    private final MemoryComponent memoryComponent;
    private final SkillComponent skillComponent;
    private final SkillTemplateEngine templateEngine;
    private final McpPort mcpPort;
    private final ToolCallExecutionService toolCallExecutionService;
    private final RagPort ragPort;
    private final BotProperties properties;
    private final AutoModeService autoModeService;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final PlanService planService;
    private final PromptSectionService promptSectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final ModelSelectionService modelSelectionService;
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
                context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, skill.getName());
                log.info("[Context] Skill transition: → {}", skill.getName());
            });
            context.clearSkillTransitionRequest();
        }

        // Resolve model tier from user preferences, active skill, and auto reflection
        // settings
        UserPreferences prefs = userPreferencesService.getPreferences();
        resolveTier(context, prefs);

        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(context.getSession());
        boolean planModeActive = sessionIdentity != null
                ? planService.isPlanModeActive(sessionIdentity)
                : planService.isPlanModeActive();
        boolean hiveSessionActive = isHiveSession(context);

        // Build memory context from structured Memory V2 pack
        String userQueryForMemory = getLastUserMessageText(context);
        String autoRunKind = context.getAttribute(ContextAttributes.AUTO_RUN_KIND);
        String autoGoalId = context.getAttribute(ContextAttributes.AUTO_GOAL_ID);
        String autoTaskId = context.getAttribute(ContextAttributes.AUTO_TASK_ID);
        String sessionScope = MemoryScopeSupport.resolveScopeFromSessionOrGlobal(context.getSession());
        List<String> scopeChain = MemoryScopeSupport.resolveScopeChain(
                context.getSession(),
                autoRunKind,
                autoGoalId,
                autoTaskId);
        MemoryQuery memoryQuery = MemoryQuery.builder()
                .queryText(userQueryForMemory)
                .activeSkill(context.getActiveSkill() != null ? context.getActiveSkill().getName() : null)
                .scope(sessionScope)
                .scopeChain(scopeChain)
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
        Map<String, ToolDefinition> toolsByName = new LinkedHashMap<>();
        toolCallExecutionService.listTools().stream()
                .filter(ToolComponent::isEnabled)
                .filter(tool -> isToolAdvertised(tool, context, planModeActive, hiveSessionActive))
                .map(ToolComponent::getDefinition)
                .forEach(tool -> putToolDefinition(toolsByName, tool, false));

        // Start MCP server and attach skill-scoped tools if active skill has MCP config
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
                        log.warn("[Context] Skipping MCP tool '{}' because adapter creation returned null",
                                mcpTool.getName());
                        continue;
                    }
                    contextScopedTools.put(mcpTool.getName(), adapter);
                    putToolDefinition(toolsByName, mcpTool, true);
                }
                log.info("[Context] Loaded {} MCP tools for skill '{}' into current turn context",
                        mcpTools.size(), context.getActiveSkill().getName());
            }
        }

        context.setAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS,
                contextScopedTools.isEmpty() ? null : contextScopedTools);
        List<ToolDefinition> tools = new ArrayList<>(toolsByName.values());
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
                    String ragContext = ragPort.query(userQuery).join();
                    if (ragContext != null && !ragContext.isBlank()) {
                        context.setAttribute(ContextAttributes.RAG_CONTEXT, ragContext);
                        log.debug("[Context] RAG context: {} chars", ragContext.length());
                    }
                } catch (Exception e) {
                    log.warn("[Context] RAG query failed: {}", e.getMessage());
                }
            }
        }

        // Set model tier for auto-mode messages when nothing else resolved it.
        if (isAutoModeMessage(context) && context.getModelTier() == null) {
            applyModelTier(context, runtimeConfigService.getAutoModelTier(), "auto_mode_default");
        }

        String workspaceInstructions = workspaceInstructionService.getWorkspaceInstructionsContext();
        log.debug("[Context] Workspace instructions: {} chars",
                workspaceInstructions != null ? workspaceInstructions.length() : 0);

        // Build system prompt
        String systemPrompt = buildSystemPrompt(context, prefs, workspaceInstructions, sessionIdentity, planModeActive);
        context.setSystemPrompt(systemPrompt);

        if (context.getActiveSkill() != null && context.getActiveSkill().getName() != null
                && !context.getActiveSkill().getName().isBlank()) {
            context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, context.getActiveSkill().getName());
        }
        ensureResolvedTierMetadata(context);

        log.info("[Context] Built context: {} tools, memory={}, skills={}, systemPrompt={} chars",
                tools.size(),
                memoryContext != null && !memoryContext.isBlank(),
                skillsSummary != null && !skillsSummary.isBlank(),
                systemPrompt.length());

        return context;
    }

    private String buildSystemPrompt(AgentContext context, UserPreferences prefs,
            String workspaceInstructions, SessionIdentity sessionIdentity, boolean planModeActive) {
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
            sb.append(
                    "Follow these repository instruction files. If instructions conflict, prefer more local files listed later.\n\n");
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
            if (isAutoReflectionContext(context)) {
                sb.append("# Auto Reflection Mode\n");
                sb.append("This autonomous run is a recovery/reflection step after repeated failures. ");
                sb.append(
                        "Diagnose the failure, identify why the previous approach failed, and propose a concrete alternative strategy for the next run.\n\n");
            }
            String autoContext = autoModeService.buildAutoContext();
            if (autoContext != null && !autoContext.isBlank()) {
                sb.append("\n").append(autoContext).append("\n");
            }
        }

        // Inject plan mode context and set model tier override
        if (planModeActive) {
            String planContext = sessionIdentity != null
                    ? planService.buildPlanContext(sessionIdentity)
                    : planService.buildPlanContext();
            if (planContext != null && !planContext.isBlank()) {
                sb.append("\n").append(planContext).append("\n");
            }
            // Override model tier if plan specifies one
            Optional<me.golemcore.bot.domain.model.Plan> activePlan = sessionIdentity != null
                    ? planService.getActivePlan(sessionIdentity)
                    : planService.getActivePlan();
            activePlan.ifPresent(plan -> {
                if (plan.getModelTier() != null && context.getModelTier() == null) {
                    applyModelTier(context, plan.getModelTier(), "plan");
                }
            });
        }

        if (isHiveSession(context)) {
            sb.append("# Hive Card Lifecycle\n");
            sb.append("This thread is bound to a Hive card. Use the `")
                    .append(TOOL_HIVE_LIFECYCLE_SIGNAL)
                    .append("` tool whenever you need to report structured board-relevant state such as blocker raised, blocker cleared, review requested, work completed, progress reported, or intentional work failure. ")
                    .append("Do not rely on plain text alone to move Hive card state. `WORK_STARTED` and interruption-driven cancellation are emitted automatically.\n\n");
        }

        return sb.toString().trim();
    }

    private boolean isToolAdvertised(ToolComponent tool, AgentContext context, boolean planModeActive,
            boolean hiveSessionActive) {
        String toolName = tool.getToolName();
        if (TOOL_PLAN_SET_CONTENT.equals(toolName) || TOOL_PLAN_GET.equals(toolName)) {
            return planModeActive;
        }
        if (ScheduleSessionActionTool.TOOL_NAME.equals(toolName)) {
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

    private void putToolDefinition(Map<String, ToolDefinition> toolsByName, ToolDefinition tool,
            boolean replaceExisting) {
        if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
            return;
        }
        if (!replaceExisting && toolsByName.containsKey(tool.getName())) {
            return;
        }
        ToolDefinition previous = toolsByName.put(tool.getName(), tool);
        if (replaceExisting && previous != null) {
            log.warn("[Context] Replaced tool definition '{}' with active MCP tool for current turn", tool.getName());
        }
    }

    private boolean isHiveSession(AgentContext context) {
        return context != null
                && context.getSession() != null
                && "hive".equalsIgnoreCase(context.getSession().getChannelType());
    }

    private void resolveTier(AgentContext context, UserPreferences prefs) {
        // Only resolve on iteration 0 (DynamicTierSystem handles later iterations)
        if (context.getCurrentIteration() != 0) {
            return;
        }

        boolean force = prefs.isTierForce();
        String userTier = prefs.getModelTier();

        if (force && userTier != null) {
            applyModelTier(context, userTier, "user_pref_forced");
            return;
        }

        if (isAutoReflectionContext(context)) {
            resolveReflectionTier(context, userTier);
            return;
        }

        Skill activeSkill = context.getActiveSkill();
        if (activeSkill != null && activeSkill.getModelTier() != null) {
            applyModelTier(context, activeSkill.getModelTier(), "skill");
        } else if (userTier != null) {
            applyModelTier(context, userTier, "user_pref");
        }
        // else: keep null. Downstream LLM execution treats null as the default tier
        // (currently "balanced") and resolves model selection accordingly.
    }

    private void resolveReflectionTier(AgentContext context, String userTier) {
        Skill activeSkill = resolveReflectionSkill(context);
        String configuredReflectionTier = resolveReflectionTierOverride(context);
        boolean priority = resolveReflectionTierPriority(context);
        String skillReflectionTier = activeSkill != null ? activeSkill.getReflectionTier() : null;

        if (priority && configuredReflectionTier != null && !configuredReflectionTier.isBlank()) {
            applyModelTier(context, configuredReflectionTier, "reflection_override");
            return;
        }
        if (skillReflectionTier != null && !skillReflectionTier.isBlank()) {
            applyModelTier(context, skillReflectionTier, "skill_reflection");
            return;
        }
        if (configuredReflectionTier != null && !configuredReflectionTier.isBlank()) {
            applyModelTier(context, configuredReflectionTier, "reflection_override");
            return;
        }

        String runtimeReflectionTier = runtimeConfigService.getAutoReflectionModelTier();
        if (runtimeReflectionTier != null && !runtimeReflectionTier.isBlank()) {
            applyModelTier(context, runtimeReflectionTier, "runtime_reflection");
            return;
        }
        if (activeSkill != null && activeSkill.getModelTier() != null) {
            applyModelTier(context, activeSkill.getModelTier(), "skill");
            return;
        }
        if (userTier != null) {
            applyModelTier(context, userTier, "user_pref");
        }
    }

    private void applyModelTier(AgentContext context, String tier, String source) {
        if (context == null) {
            return;
        }
        context.setModelTier(tier);
        if (source != null && !source.isBlank()) {
            context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, source);
        }
        updateResolvedTierMetadata(context);
    }

    private void ensureResolvedTierMetadata(AgentContext context) {
        if (context == null) {
            return;
        }
        if (context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE) == null) {
            context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "implicit_default");
        }
        updateResolvedTierMetadata(context);
    }

    private void updateResolvedTierMetadata(AgentContext context) {
        if (context == null || modelSelectionService == null) {
            return;
        }
        try {
            ModelSelectionService.ModelSelection selection = modelSelectionService
                    .resolveForTier(context.getModelTier());
            if (selection.model() != null && !selection.model().isBlank()) {
                context.setAttribute(ContextAttributes.MODEL_TIER_MODEL_ID, selection.model());
            }
            if (selection.reasoning() != null && !selection.reasoning().isBlank()) {
                context.setAttribute(ContextAttributes.MODEL_TIER_REASONING, selection.reasoning());
            } else if (context.getAttributes() != null) {
                context.getAttributes().remove(ContextAttributes.MODEL_TIER_REASONING);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (context.getAttributes() != null) {
                context.getAttributes().remove(ContextAttributes.MODEL_TIER_MODEL_ID);
                context.getAttributes().remove(ContextAttributes.MODEL_TIER_REASONING);
            }
        }
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private boolean isAutoReflectionContext(AgentContext context) {
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE))) {
            return true;
        }
        Message last = getLastMessage(context);
        return last != null && last.getMetadata() != null
                && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE));
    }

    private String resolveReflectionTierOverride(AgentContext context) {
        String configured = context.getAttribute(ContextAttributes.AUTO_REFLECTION_TIER);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Message last = getLastMessage(context);
        return last != null ? AutoRunContextSupport.readMetadataString(last.getMetadata(),
                ContextAttributes.AUTO_REFLECTION_TIER) : null;
    }

    private Skill resolveReflectionSkill(AgentContext context) {
        String skillName = resolveReflectionSkillName(context);
        if (skillName != null && !skillName.isBlank()) {
            Optional<Skill> reflectedSkill = skillComponent.findByName(skillName);
            if (reflectedSkill.isPresent()) {
                return reflectedSkill.get();
            }
        }
        return context.getActiveSkill();
    }

    private String resolveReflectionSkillName(AgentContext context) {
        String explicit = context.getAttribute(ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        explicit = context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        Message last = getLastMessage(context);
        if (last == null || last.getMetadata() == null) {
            return null;
        }
        String runSkill = AutoRunContextSupport.readMetadataString(last.getMetadata(),
                ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        if (runSkill != null && !runSkill.isBlank()) {
            return runSkill;
        }
        return AutoRunContextSupport.readMetadataString(last.getMetadata(), ContextAttributes.ACTIVE_SKILL_NAME);
    }

    private boolean resolveReflectionTierPriority(AgentContext context) {
        Boolean priority = context.getAttribute(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
        if (priority != null) {
            return priority;
        }
        Message last = getLastMessage(context);
        if (last == null || last.getMetadata() == null) {
            return false;
        }
        Object value = last.getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    private Message getLastMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        return context.getMessages().get(context.getMessages().size() - 1);
    }

    private String getLastUserMessageText(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage() && !msg.isInternalMessage()) {
                return msg.getContent();
            }
        }
        return null;
    }
}
