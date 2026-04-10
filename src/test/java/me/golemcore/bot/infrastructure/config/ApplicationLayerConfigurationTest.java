package me.golemcore.bot.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.application.models.ModelManagementFacade;
import me.golemcore.bot.application.models.ModelRegistryService;
import me.golemcore.bot.application.prompts.PromptManagementFacade;
import me.golemcore.bot.application.models.ProviderModelDiscoveryService;
import me.golemcore.bot.application.scheduler.SchedulerFacade;
import me.golemcore.bot.application.settings.RuntimeSettingsFacade;
import me.golemcore.bot.application.settings.RuntimeSettingsMergeService;
import me.golemcore.bot.application.settings.RuntimeSettingsValidator;
import me.golemcore.bot.application.skills.SkillManagementFacade;
import me.golemcore.bot.application.skills.SkillMarketplaceService;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SkillDocumentService;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspacePathService;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.ModelRegistryCachePort;
import me.golemcore.bot.port.outbound.ModelRegistryDocumentPort;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import me.golemcore.bot.port.outbound.ModelRegistryRemotePort;
import me.golemcore.bot.port.outbound.ProviderModelDiscoveryPort;
import me.golemcore.bot.port.outbound.SkillSettingsPort;
import me.golemcore.bot.port.outbound.StoragePort;
import me.golemcore.bot.port.outbound.VoiceProviderCatalogPort;
import org.junit.jupiter.api.Test;

class ApplicationLayerConfigurationTest {

    private final ApplicationLayerConfiguration configuration = new ApplicationLayerConfiguration();

    @Test
    void shouldCreateSettingsBeans() {
        RuntimeSettingsMergeService mergeService = configuration.runtimeSettingsMergeService();
        RuntimeSettingsValidator validator = configuration.runtimeSettingsValidator(
                mock(ModelSelectionService.class),
                mock(VoiceProviderCatalogPort.class));
        RuntimeSettingsFacade facade = configuration.runtimeSettingsFacade(
                mock(RuntimeConfigService.class),
                mock(UserPreferencesService.class),
                mock(MemoryPresetService.class),
                mock(HiveManagedPolicyService.class),
                validator,
                mergeService);

        assertInstanceOf(RuntimeSettingsMergeService.class, mergeService);
        assertInstanceOf(RuntimeSettingsValidator.class, validator);
        assertInstanceOf(RuntimeSettingsFacade.class, facade);
    }

    @Test
    void shouldCreateCommandBeans() {
        ModelSelectionCommandService modelSelection = configuration.modelSelectionCommandService(
                mock(UserPreferencesService.class),
                mock(ModelSelectionService.class),
                mock(RuntimeConfigService.class));
        AutomationCommandService automation = configuration.automationCommandService(
                mock(AutoModeService.class),
                mock(RuntimeConfigService.class),
                mock(ScheduleService.class),
                mock(DelayedActionPolicyService.class),
                mock(DelayedSessionActionService.class));
        PlanCommandService plans = configuration.planCommandService(
                mock(PlanService.class),
                mock(PlanExecutionService.class),
                mock(RuntimeConfigService.class));

        assertNotNull(modelSelection);
        assertNotNull(automation);
        assertNotNull(plans);
    }

    @Test
    void shouldCreateSchedulerAndSkillFacades() {
        SchedulerFacade scheduler = configuration.schedulerFacade(
                mock(AutoModeService.class),
                mock(ScheduleService.class),
                mock(RuntimeConfigService.class),
                mock(ChannelRuntimePort.class));
        ModelRegistryService modelRegistry = configuration.modelRegistryService(
                mock(RuntimeConfigService.class),
                mock(ModelRegistryRemotePort.class),
                mock(ModelRegistryCachePort.class),
                mock(ModelRegistryDocumentPort.class));
        ProviderModelDiscoveryService discovery = configuration.providerModelDiscoveryService(
                mock(RuntimeConfigService.class),
                mock(ProviderModelDiscoveryPort.class));
        ModelManagementFacade models = configuration.modelManagementFacade(
                mock(ModelConfigAdminPort.class),
                mock(ModelSelectionService.class),
                discovery,
                modelRegistry,
                mock(LlmPort.class),
                mock(HiveManagedPolicyService.class));
        SkillMarketplaceService marketplace = configuration.skillMarketplaceService(
                mock(SkillSettingsPort.class),
                mock(StoragePort.class),
                mock(SkillService.class),
                mock(RuntimeConfigService.class),
                mock(WorkspacePathService.class),
                new SkillDocumentService());
        PromptManagementFacade prompts = configuration.promptManagementFacade(
                mock(PromptSectionService.class),
                mock(UserPreferencesService.class),
                mock(StoragePort.class));
        SkillManagementFacade skills = configuration.skillManagementFacade(
                mock(SkillService.class),
                new SkillDocumentService(),
                marketplace,
                mock(McpPort.class),
                mock(StoragePort.class));

        assertInstanceOf(SchedulerFacade.class, scheduler);
        assertNotNull(modelRegistry);
        assertNotNull(discovery);
        assertNotNull(models);
        assertNotNull(marketplace);
        assertNotNull(prompts);
        assertInstanceOf(SkillManagementFacade.class, skills);
    }
}
