package me.golemcore.bot.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;
import me.golemcore.bot.application.update.UpdateService;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.domain.service.UpdateMaintenanceWindow;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.PlanFinalizationSystem;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.PlanReadyNotificationPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import me.golemcore.bot.port.outbound.ScheduleCronPort;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.UpdateArtifactStorePort;
import me.golemcore.bot.port.outbound.UpdateRestartPort;
import me.golemcore.bot.port.outbound.UpdateRuntimeConfigPort;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import org.junit.jupiter.api.Test;

class CoreLayerConfigurationTest {

    private final CoreLayerConfiguration configuration = new CoreLayerConfiguration();

    @Test
    void shouldCreateScheduleAndUpdateServices() {
        ScheduleService scheduleService = configuration.scheduleService(
                mock(SchedulePersistencePort.class),
                mock(ScheduleCronPort.class),
                Clock.systemUTC());
        UpdateService updateService = configuration.updateService(
                mock(UpdateSettingsPort.class),
                mock(UpdateVersionPort.class),
                mock(UpdateRuntimeConfigPort.class),
                mock(UpdateRestartPort.class),
                mock(UpdateArtifactStorePort.class),
                Clock.systemUTC(),
                mock(UpdateActivityGate.class),
                mock(UpdateMaintenanceWindow.class),
                List.of(mock(ReleaseSourcePort.class)));

        assertInstanceOf(ScheduleService.class, scheduleService);
        assertInstanceOf(UpdateService.class, updateService);
    }

    @Test
    void shouldCreateAgentLoopAndPlanFinalizationSystem() {
        AgentLoop agentLoop = configuration.agentLoop(
                mock(SessionPort.class),
                mock(RateLimitPort.class),
                List.of(mock(AgentSystem.class)),
                mock(ChannelRuntimePort.class),
                mock(RuntimeConfigService.class),
                mock(UserPreferencesService.class),
                mock(LlmPort.class),
                Clock.systemUTC(),
                mock(TraceService.class));
        PlanFinalizationSystem planFinalizationSystem = configuration.planFinalizationSystem(
                mock(PlanService.class),
                mock(PlanReadyNotificationPort.class));

        assertNotNull(agentLoop);
        assertInstanceOf(PlanFinalizationSystem.class, planFinalizationSystem);
    }

    @Test
    void shouldCreateContextLayerBeans() {
        ContextLayerConfiguration contextLayerConfiguration = new ContextLayerConfiguration();

        assertNotNull(contextLayerConfiguration.promptComposer());
        assertNotNull(
                contextLayerConfiguration.skillResolver(mock(me.golemcore.bot.domain.component.SkillComponent.class)));
        assertNotNull(contextLayerConfiguration.tierResolver(
                mock(UserPreferencesService.class),
                mock(me.golemcore.bot.domain.service.ModelSelectionService.class),
                mock(RuntimeConfigService.class),
                mock(me.golemcore.bot.domain.component.SkillComponent.class)));
        assertNotNull(contextLayerConfiguration.identityLayer(
                mock(me.golemcore.bot.domain.service.PromptSectionService.class),
                mock(UserPreferencesService.class)));
        assertNotNull(contextLayerConfiguration.workspaceInstructionsLayer(
                mock(me.golemcore.bot.domain.service.WorkspaceInstructionService.class)));
        assertNotNull(contextLayerConfiguration.memoryLayer(
                mock(me.golemcore.bot.domain.component.MemoryComponent.class),
                mock(RuntimeConfigService.class),
                mock(me.golemcore.bot.domain.service.MemoryPresetService.class)));
        assertNotNull(contextLayerConfiguration.ragLayer(mock(me.golemcore.bot.port.outbound.RagPort.class)));
        assertNotNull(contextLayerConfiguration.skillLayer(
                mock(me.golemcore.bot.domain.component.SkillComponent.class),
                mock(me.golemcore.bot.domain.service.SkillTemplateEngine.class)));
        assertNotNull(contextLayerConfiguration.toolLayer(
                mock(me.golemcore.bot.domain.service.ToolCallExecutionService.class),
                mock(me.golemcore.bot.port.outbound.McpPort.class),
                mock(PlanService.class),
                mock(me.golemcore.bot.domain.service.DelayedActionPolicyService.class)));
        assertNotNull(contextLayerConfiguration.tierAwarenessLayer(mock(UserPreferencesService.class)));
        assertNotNull(
                contextLayerConfiguration.autoModeLayer(mock(me.golemcore.bot.domain.service.AutoModeService.class)));
        assertNotNull(contextLayerConfiguration.planModeLayer(mock(PlanService.class)));
        assertNotNull(contextLayerConfiguration.hiveLayer());
        assertNotNull(contextLayerConfiguration.webhookResponseSchemaLayer());
        assertNotNull(contextLayerConfiguration.contextAssembler(
                mock(me.golemcore.bot.domain.context.resolution.SkillResolver.class),
                mock(me.golemcore.bot.domain.context.resolution.TierResolver.class),
                List.of(mock(me.golemcore.bot.domain.context.ContextLayer.class)),
                mock(me.golemcore.bot.domain.context.PromptComposer.class)));
    }

}
