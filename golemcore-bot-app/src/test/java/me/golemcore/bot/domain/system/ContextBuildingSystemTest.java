package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.PromptComposer;
import me.golemcore.bot.domain.context.layer.ToolLayer;
import me.golemcore.bot.domain.context.resolution.SkillResolver;
import me.golemcore.bot.domain.context.resolution.TierResolver;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.tools.HiveLifecycleSignalTool;
import me.golemcore.bot.tools.PlanGetTool;
import me.golemcore.bot.tools.PlanSetContentTool;
import me.golemcore.bot.tools.ScheduleSessionActionTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextBuildingSystemTest {

    @Test
    void shouldAdvertisePlanSetContentToolOnlyWhenPlanModeIsActive() {
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        PlanService planService = mock(PlanService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);

        when(planService.isFeatureEnabled()).thenReturn(true);
        PlanSetContentTool planFinalizeTool = new PlanSetContentTool(planService);
        PlanGetTool planGetTool = new PlanGetTool(planService);
        when(toolCallExecutionService.listTools()).thenReturn(List.of(planFinalizeTool, planGetTool));

        ToolLayer toolLayer = new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService);
        ContextAssembler assembler = buildAssembler(skillComponent, toolLayer);

        ContextBuildingSystem system = new ContextBuildingSystem(assembler);

        AgentContext context = AgentContext.builder().build();

        when(planService.isPlanModeActive()).thenReturn(false);
        system.process(context);
        assertTrue(context.getAvailableTools().stream()
                .noneMatch(t -> PlanSetContentTool.TOOL_NAME.equals(t.getName())
                        || PlanGetTool.TOOL_NAME.equals(t.getName())));

        when(planService.isPlanModeActive()).thenReturn(true);
        system.process(context);
        assertTrue(context.getAvailableTools().stream()
                .anyMatch(t -> PlanSetContentTool.TOOL_NAME.equals(t.getName())));
        assertTrue(context.getAvailableTools().stream()
                .anyMatch(t -> PlanGetTool.TOOL_NAME.equals(t.getName())));
    }

    @Test
    void shouldAdvertiseHiveLifecycleToolOnlyForHiveSessions() {
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        PlanService planService = mock(PlanService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isHiveSdlcLifecycleSignalEnabled()).thenReturn(true);

        HiveLifecycleSignalTool hiveLifecycleSignalTool = new HiveLifecycleSignalTool(
                mock(me.golemcore.bot.port.outbound.HiveEventPublishPort.class),
                runtimeConfigService,
                Clock.systemUTC());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(hiveLifecycleSignalTool));

        ToolLayer toolLayer = new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService);
        ContextAssembler assembler = buildAssembler(skillComponent, toolLayer);

        ContextBuildingSystem system = new ContextBuildingSystem(assembler);

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
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        PlanService planService = mock(PlanService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);

        ToolComponent delayedTool = mock(ToolComponent.class);
        when(delayedTool.isEnabled()).thenReturn(true);
        when(delayedTool.getToolName()).thenReturn(ScheduleSessionActionTool.TOOL_NAME);
        when(delayedTool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(ScheduleSessionActionTool.TOOL_NAME)
                .description("Delayed actions")
                .build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(delayedTool));
        when(delayedActionPolicyService.canScheduleActions("webhook")).thenReturn(false);

        ToolLayer toolLayer = new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService);
        ContextAssembler assembler = buildAssembler(skillComponent, toolLayer);

        ContextBuildingSystem system = new ContextBuildingSystem(assembler);

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
        SkillComponent skillComponent = mock(SkillComponent.class);
        ToolCallExecutionService toolCallExecutionService = mock(ToolCallExecutionService.class);
        McpPort mcpPort = mock(McpPort.class);
        PlanService planService = mock(PlanService.class);
        DelayedActionPolicyService delayedActionPolicyService = mock(DelayedActionPolicyService.class);

        ToolComponent delayedTool = mock(ToolComponent.class);
        when(delayedTool.isEnabled()).thenReturn(true);
        when(delayedTool.getToolName()).thenReturn(ScheduleSessionActionTool.TOOL_NAME);
        when(delayedTool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(ScheduleSessionActionTool.TOOL_NAME)
                .description("Delayed actions")
                .build());
        when(toolCallExecutionService.listTools()).thenReturn(List.of(delayedTool));
        when(delayedActionPolicyService.canScheduleActions("web")).thenReturn(true);

        ToolLayer toolLayer = new ToolLayer(toolCallExecutionService, mcpPort, planService, delayedActionPolicyService);
        ContextAssembler assembler = buildAssembler(skillComponent, toolLayer);

        ContextBuildingSystem system = new ContextBuildingSystem(assembler);

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

    @Test
    void shouldAssembleContextWhenContextAssemblerReturnsResult() {
        ContextAssembler assembler = mock(ContextAssembler.class);
        ContextBuildingSystem system = new ContextBuildingSystem(assembler);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-4").chatId("chat-4").build())
                .build();
        when(assembler.assemble(context)).thenReturn(context);

        AgentContext result = system.process(context);

        verify(assembler).assemble(context);
        assertEquals(context, result);
    }

    @Test
    void shouldReturnNullWhenAssemblerReturnsNull() {
        ContextAssembler assembler = mock(ContextAssembler.class);
        ContextBuildingSystem system = new ContextBuildingSystem(assembler);
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-6").chatId("chat-6").build())
                .build();
        when(assembler.assemble(context)).thenReturn(null);

        AgentContext result = system.process(context);

        verify(assembler).assemble(context);
        assertNull(result);
    }

    private ContextAssembler buildAssembler(SkillComponent skillComponent, ToolLayer toolLayer) {
        UserPreferencesService userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5-balanced", "medium"));

        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);

        SkillResolver skillResolver = new SkillResolver(skillComponent);
        TierResolver tierResolver = new TierResolver(
                userPreferencesService,
                modelSelectionService,
                runtimeConfigService,
                skillComponent);

        List<ContextLayer> layers = List.of(toolLayer);
        PromptComposer promptComposer = new PromptComposer();

        return new ContextAssembler(skillResolver, tierResolver, layers, promptComposer);
    }
}
