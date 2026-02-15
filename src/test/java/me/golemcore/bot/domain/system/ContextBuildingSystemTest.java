package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
import me.golemcore.bot.tools.PlanFinalizeTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBuildingSystemTest {

    @Test
    void shouldAdvertisePlanFinalizeToolOnlyWhenPlanModeIsActive() {
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

        PlanService planService = mock(PlanService.class);
        when(planService.isFeatureEnabled()).thenReturn(true);

        PlanFinalizeTool planFinalizeTool = new PlanFinalizeTool(planService);

        ContextBuildingSystem system = new ContextBuildingSystem(
                memoryComponent,
                skillComponent,
                List.of(planFinalizeTool),
                templateEngine,
                mcpPort,
                toolCallExecutionService,
                ragPort,
                properties,
                autoModeService,
                planService,
                promptSectionService,
                userPreferencesService);

        AgentContext context = AgentContext.builder().build();

        // Case 1: plan mode OFF
        when(planService.isPlanModeActive()).thenReturn(false);
        system.process(context);
        assertTrue(context.getAvailableTools().stream()
                .noneMatch(t -> PlanFinalizeTool.TOOL_NAME.equals(t.getName())));

        // Case 2: plan mode ON
        when(planService.isPlanModeActive()).thenReturn(true);
        system.process(context);
        assertTrue(context.getAvailableTools().stream()
                .anyMatch(t -> PlanFinalizeTool.TOOL_NAME.equals(t.getName())));
    }
}
