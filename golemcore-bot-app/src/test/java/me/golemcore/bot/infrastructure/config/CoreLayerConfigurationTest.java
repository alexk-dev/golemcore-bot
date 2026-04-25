package me.golemcore.bot.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;
import me.golemcore.bot.application.update.UpdateService;
import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.PromptComposer;
import me.golemcore.bot.domain.context.resolution.SkillResolver;
import me.golemcore.bot.domain.context.resolution.TierResolver;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.ContextHygieneService;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.domain.service.UpdateMaintenanceWindow;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
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
    void shouldCreateAgentLoop() {
        AgentLoop agentLoop = configuration.agentLoop(
                mock(SessionPort.class),
                mock(RateLimitPort.class),
                List.of(mock(AgentSystem.class)),
                mock(ChannelRuntimePort.class),
                mock(RuntimeConfigService.class),
                mock(UserPreferencesService.class),
                mock(LlmPort.class),
                Clock.systemUTC(),
                mock(TraceService.class),
                mock(ContextHygieneService.class));

        assertNotNull(agentLoop);
    }

    @Test
    void shouldCreateSharedCompactionBeans() {
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        ModelSelectionService modelSelectionService = mock(ModelSelectionService.class);

        ContextTokenEstimator estimator = CoreLayerConfiguration.contextTokenEstimator();
        ContextCompactionPolicy policy = configuration.contextCompactionPolicy(runtimeConfigService,
                modelSelectionService);
        PlanModeToolRestrictionService planModeToolRestrictionService = configuration
                .planModeToolRestrictionService(mock(PlanService.class));

        assertNotNull(estimator);
        assertNotNull(policy);
        assertNotNull(planModeToolRestrictionService);
    }

    @Test
    void shouldCreateContextLayerBeans() {
        ContextLayerConfiguration contextLayerConfiguration = new ContextLayerConfiguration();

        assertNotNull(contextLayerConfiguration.promptComposer());
        assertNotNull(contextLayerConfiguration.skillResolver(mock(SkillComponent.class)));
        assertNotNull(contextLayerConfiguration.tierResolver(
                mock(UserPreferencesService.class),
                mock(ModelSelectionService.class),
                mock(RuntimeConfigService.class),
                mock(SkillComponent.class)));
        assertNotNull(contextLayerConfiguration.identityLayer(
                mock(PromptSectionService.class),
                mock(UserPreferencesService.class)));
        assertNotNull(contextLayerConfiguration.workspaceInstructionsLayer(
                mock(WorkspaceInstructionService.class)));
        assertNotNull(contextLayerConfiguration.memoryLayer(
                mock(MemoryComponent.class),
                mock(RuntimeConfigService.class),
                mock(MemoryPresetService.class)));
        assertNotNull(contextLayerConfiguration.ragLayer(mock(RagPort.class)));
        assertNotNull(contextLayerConfiguration.skillLayer(
                mock(SkillComponent.class),
                mock(SkillTemplateEngine.class)));
        assertNotNull(contextLayerConfiguration.toolLayer(
                mock(ToolCallExecutionService.class),
                mock(McpPort.class),
                mock(DelayedActionPolicyService.class),
                mock(PlanModeToolRestrictionService.class)));
        assertNotNull(contextLayerConfiguration.tierAwarenessLayer(mock(UserPreferencesService.class)));
        assertNotNull(contextLayerConfiguration.autoModeLayer(mock(AutoModeService.class)));
        assertNotNull(contextLayerConfiguration.planModeLayer(mock(PlanService.class)));
        assertNotNull(contextLayerConfiguration.webhookResponseSchemaLayer());
        assertNotNull(contextLayerConfiguration.contextAssembler(
                mock(SkillResolver.class),
                mock(TierResolver.class),
                List.of(mock(ContextLayer.class)),
                mock(PromptComposer.class),
                mock(ContextCompactionPolicy.class)));
    }

}
