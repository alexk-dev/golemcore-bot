package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.application.models.ModelManagementFacade;
import me.golemcore.bot.application.models.ModelRegistryService;
import me.golemcore.bot.application.models.ProviderModelDiscoveryService;
import me.golemcore.bot.application.prompts.PromptManagementFacade;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationLayerConfiguration {

    @Bean
    RuntimeSettingsMergeService runtimeSettingsMergeService() {
        return new RuntimeSettingsMergeService();
    }

    @Bean
    RuntimeSettingsValidator runtimeSettingsValidator(
            ModelSelectionService modelSelectionService,
            VoiceProviderCatalogPort voiceProviderCatalogPort) {
        return new RuntimeSettingsValidator(modelSelectionService, voiceProviderCatalogPort);
    }

    @Bean
    RuntimeSettingsFacade runtimeSettingsFacade(
            RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            MemoryPresetService memoryPresetService,
            HiveManagedPolicyService hiveManagedPolicyService,
            RuntimeSettingsValidator validator,
            RuntimeSettingsMergeService mergeService) {
        return new RuntimeSettingsFacade(
                runtimeConfigService,
                preferencesService,
                memoryPresetService,
                hiveManagedPolicyService,
                validator,
                mergeService);
    }

    @Bean
    ModelSelectionCommandService modelSelectionCommandService(
            UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeConfigService runtimeConfigService) {
        return new ModelSelectionCommandService(preferencesService, modelSelectionService, runtimeConfigService);
    }

    @Bean
    AutomationCommandService automationCommandService(
            AutoModeService autoModeService,
            RuntimeConfigService runtimeConfigService,
            ScheduleService scheduleService,
            DelayedActionPolicyService delayedActionPolicyService,
            DelayedSessionActionService delayedSessionActionService) {
        return new AutomationCommandService(
                autoModeService,
                runtimeConfigService,
                scheduleService,
                delayedActionPolicyService,
                delayedSessionActionService);
    }

    @Bean
    PlanCommandService planCommandService(
            PlanService planService,
            PlanExecutionService planExecutionService,
            RuntimeConfigService runtimeConfigService) {
        return new PlanCommandService(planService, planExecutionService, runtimeConfigService);
    }

    @Bean
    SchedulerFacade schedulerFacade(
            AutoModeService autoModeService,
            ScheduleService scheduleService,
            RuntimeConfigService runtimeConfigService,
            ChannelRuntimePort channelRuntimePort) {
        return new SchedulerFacade(autoModeService, scheduleService, runtimeConfigService, channelRuntimePort);
    }

    @Bean
    ModelRegistryService modelRegistryService(
            RuntimeConfigService runtimeConfigService,
            ModelRegistryRemotePort modelRegistryRemotePort,
            ModelRegistryCachePort modelRegistryCachePort,
            ModelRegistryDocumentPort modelRegistryDocumentPort) {
        return new ModelRegistryService(
                runtimeConfigService,
                modelRegistryRemotePort,
                modelRegistryCachePort,
                modelRegistryDocumentPort);
    }

    @Bean
    ProviderModelDiscoveryService providerModelDiscoveryService(
            RuntimeConfigService runtimeConfigService,
            ProviderModelDiscoveryPort providerModelDiscoveryPort) {
        return new ProviderModelDiscoveryService(runtimeConfigService, providerModelDiscoveryPort);
    }

    @Bean
    ModelManagementFacade modelManagementFacade(
            ModelConfigAdminPort modelConfigAdminPort,
            ModelSelectionService modelSelectionService,
            ProviderModelDiscoveryService providerModelDiscoveryService,
            ModelRegistryService modelRegistryService,
            LlmPort llmPort,
            HiveManagedPolicyService hiveManagedPolicyService) {
        return new ModelManagementFacade(
                modelConfigAdminPort,
                modelSelectionService,
                providerModelDiscoveryService,
                modelRegistryService,
                llmPort,
                hiveManagedPolicyService);
    }

    @Bean
    SkillMarketplaceService skillMarketplaceService(
            SkillSettingsPort skillSettingsPort,
            StoragePort storagePort,
            SkillService skillService,
            RuntimeConfigService runtimeConfigService,
            WorkspacePathService workspacePathService,
            SkillDocumentService skillDocumentService) {
        return new SkillMarketplaceService(
                skillSettingsPort,
                storagePort,
                skillService,
                runtimeConfigService,
                workspacePathService,
                skillDocumentService);
    }

    @Bean
    PromptManagementFacade promptManagementFacade(
            PromptSectionService promptSectionService,
            UserPreferencesService userPreferencesService,
            StoragePort storagePort) {
        return new PromptManagementFacade(promptSectionService, userPreferencesService, storagePort);
    }

    @Bean
    SkillManagementFacade skillManagementFacade(
            SkillService skillService,
            SkillDocumentService skillDocumentService,
            SkillMarketplaceService skillMarketplaceService,
            McpPort mcpPort,
            StoragePort storagePort) {
        return new SkillManagementFacade(
                skillService,
                skillDocumentService,
                skillMarketplaceService,
                mcpPort,
                storagePort);
    }
}
