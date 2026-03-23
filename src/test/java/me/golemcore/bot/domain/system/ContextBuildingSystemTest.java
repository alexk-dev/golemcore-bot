package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.ModelSelectionService;
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
import me.golemcore.bot.tools.HiveLifecycleSignalTool;
import me.golemcore.bot.tools.PlanSetContentTool;
import me.golemcore.bot.tools.PlanGetTool;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBuildingSystemTest {

    @Test
    void shouldAdvertisePlanSetContentToolOnlyWhenPlanModeIsActive() {
        BotProperties properties = new BotProperties();

        SkillTemplateEngine templateEngine = mock(SkillTemplateEngine.class);
        PromptSectionService promptSectionService = mock(PromptSectionService.class);
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        MemoryComponent memoryComponent = mock(MemoryComponent.class);
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        RagPort ragPort = mock(RagPort.class);
        AutoModeService autoModeService = mock(AutoModeService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-balanced", "medium"));
        WorkspaceInstructionService workspaceInstructionService = mock(WorkspaceInstructionService.class);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");

        PlanService planService = mock(PlanService.class);
        when(planService.isFeatureEnabled()).thenReturn(true);

        PlanSetContentTool planFinalizeTool = new PlanSetContentTool(planService);
        PlanGetTool planGetTool = new PlanGetTool(planService);
        when(toolCallExecutionService.listTools()).thenReturn(List.of(planFinalizeTool, planGetTool));

        ContextBuildingSystem system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                delayedActionPolicyService,
                planService,
                promptSectionService,
                runtimeConfigService,
                modelSelectionService,
                userPreferencesService,
                workspaceInstructionService);

        AgentContext context = AgentContext.builder().build();

        // Case 1: plan mode OFF
        when(planService.isPlanModeActive()).thenReturn(false);
        system.process(context);
        assertTrue(context.getAvailableTools().stream()
                .noneMatch(t -> PlanSetContentTool.TOOL_NAME.equals(t.getName())
                        || PlanGetTool.TOOL_NAME.equals(t.getName())));

        // Case 2: plan mode ON
        when(planService.isPlanModeActive()).thenReturn(true);
        system.process(context);
        assertTrue(context.getAvailableTools().stream()
                .anyMatch(t -> PlanSetContentTool.TOOL_NAME.equals(t.getName())));
        assertTrue(context.getAvailableTools().stream()
                .anyMatch(t -> PlanGetTool.TOOL_NAME.equals(t.getName())));
    }

    @Test
    void shouldAdvertiseHiveLifecycleToolOnlyForHiveSessions() {
        BotProperties properties = new BotProperties();

        SkillTemplateEngine templateEngine = mock(SkillTemplateEngine.class);
        PromptSectionService promptSectionService = mock(PromptSectionService.class);
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        MemoryComponent memoryComponent = mock(MemoryComponent.class);
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        RagPort ragPort = mock(RagPort.class);
        AutoModeService autoModeService = mock(AutoModeService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-balanced", "medium"));
        WorkspaceInstructionService workspaceInstructionService = mock(WorkspaceInstructionService.class);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");

        PlanService planService = mock(PlanService.class);
        when(planService.isFeatureEnabled()).thenReturn(true);

        HiveLifecycleSignalTool hiveLifecycleSignalTool = new HiveLifecycleSignalTool(
                mock(me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher.class),
                Clock.systemUTC());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(hiveLifecycleSignalTool));

        ContextBuildingSystem system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                delayedActionPolicyService,
                planService,
                promptSectionService,
                runtimeConfigService,
                modelSelectionService,
                userPreferencesService,
                workspaceInstructionService);

        AgentContext webContext = AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();

        system.process(webContext);

        assertTrue(webContext.getAvailableTools().stream()
                .noneMatch(t -> HiveLifecycleSignalTool.TOOL_NAME.equals(t.getName())));

        AgentContext hiveContext = AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("hive")
                        .chatId("thread-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();

        system.process(hiveContext);

        assertTrue(hiveContext.getAvailableTools().stream()
                .anyMatch(t -> HiveLifecycleSignalTool.TOOL_NAME.equals(t.getName())));
    }

    @Test
    void shouldNotAdvertiseDelayedActionToolForWebhookSession() {
        BotProperties properties = new BotProperties();

        SkillTemplateEngine templateEngine = mock(SkillTemplateEngine.class);
        PromptSectionService promptSectionService = mock(PromptSectionService.class);
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        MemoryComponent memoryComponent = mock(MemoryComponent.class);
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        RagPort ragPort = mock(RagPort.class);
        AutoModeService autoModeService = mock(AutoModeService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-balanced", "medium"));
        WorkspaceInstructionService workspaceInstructionService = mock(WorkspaceInstructionService.class);
        PlanService planService = mock(PlanService.class);
        ToolComponent delayedTool = mock(ToolComponent.class);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");
        when(delayedTool.isEnabled()).thenReturn(true);
        when(delayedTool.getToolName()).thenReturn(ScheduleSessionActionTool.TOOL_NAME);
        when(delayedTool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(ScheduleSessionActionTool.TOOL_NAME)
                .description("Delayed actions")
                .build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(delayedTool));
        when(delayedActionPolicyService.canScheduleActions("webhook")).thenReturn(false);

        ContextBuildingSystem system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                delayedActionPolicyService,
                planService,
                promptSectionService,
                runtimeConfigService,
                modelSelectionService,
                userPreferencesService,
                workspaceInstructionService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("webhook")
                        .chatId("conv-1")
                        .build())
                .build();

        system.process(context);

        assertTrue(context.getAvailableTools().stream()
                .noneMatch(t -> ScheduleSessionActionTool.TOOL_NAME.equals(t.getName())));
    }

    @Test
    void shouldAdvertiseDelayedActionToolWhenSchedulingIsPossibleWithoutProactivePush() {
        BotProperties properties = new BotProperties();

        SkillTemplateEngine templateEngine = mock(SkillTemplateEngine.class);
        PromptSectionService promptSectionService = mock(PromptSectionService.class);
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        MemoryComponent memoryComponent = mock(MemoryComponent.class);
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        RagPort ragPort = mock(RagPort.class);
        AutoModeService autoModeService = mock(AutoModeService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-balanced", "medium"));
        WorkspaceInstructionService workspaceInstructionService = mock(WorkspaceInstructionService.class);
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");
        PlanService planService = mock(PlanService.class);

        ToolComponent delayedTool = mock(ToolComponent.class);
        when(delayedTool.isEnabled()).thenReturn(true);
        when(delayedTool.getToolName()).thenReturn(ScheduleSessionActionTool.TOOL_NAME);
        when(delayedTool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(ScheduleSessionActionTool.TOOL_NAME)
                .description("Delayed actions")
                .build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(delayedTool));
        when(delayedActionPolicyService.canScheduleActions("web")).thenReturn(true);
        when(delayedActionPolicyService.supportsProactiveMessage("web", "chat-1")).thenReturn(false);

        ContextBuildingSystem system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                delayedActionPolicyService,
                planService,
                promptSectionService,
                runtimeConfigService,
                modelSelectionService,
                userPreferencesService,
                workspaceInstructionService);

        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .channelType("web")
                        .chatId("chat-1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .build();

        system.process(context);

        assertTrue(context.getAvailableTools().stream()
                .anyMatch(t -> ScheduleSessionActionTool.TOOL_NAME.equals(t.getName())));
    }
}
