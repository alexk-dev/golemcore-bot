package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.adapter.outbound.skills.SkillMarketplaceLegacySupport;
import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.application.models.ModelManagementFacade;
import me.golemcore.bot.application.models.ModelRegistryService;
import me.golemcore.bot.application.models.ProviderModelDiscoveryService;
import me.golemcore.bot.application.models.ProviderModelImportService;
import me.golemcore.bot.application.prompts.PromptManagementFacade;
import me.golemcore.bot.application.scheduler.SchedulerFacade;
import me.golemcore.bot.application.selfevolving.tactic.TacticEmbeddingProbeService;
import me.golemcore.bot.application.settings.RuntimeSettingsFacade;
import me.golemcore.bot.application.settings.RuntimeSettingsMergeService;
import me.golemcore.bot.application.settings.RuntimeSettingsValidator;
import me.golemcore.bot.application.skills.SkillManagementFacade;
import me.golemcore.bot.application.skills.SkillMarketplaceService;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ActiveSessionPointerService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.service.HiveSessionStateStore;
import me.golemcore.bot.domain.service.HiveSsoService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionRetentionCleanupService;
import me.golemcore.bot.domain.service.SkillDocumentService;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspacePathService;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import me.golemcore.bot.port.outbound.ModelRegistryCachePort;
import me.golemcore.bot.port.outbound.ModelRegistryDocumentPort;
import me.golemcore.bot.port.outbound.ModelRegistryRemotePort;
import me.golemcore.bot.port.outbound.ProviderModelDiscoveryPort;
import me.golemcore.bot.port.outbound.ResponseJsonSchemaValidatorPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceArtifactPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceCatalogPort;
import me.golemcore.bot.port.outbound.SkillMarketplaceInstallPort;
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
            VoiceProviderCatalogPort voiceProviderCatalogPort,
            ResponseJsonSchemaValidatorPort responseJsonSchemaValidatorPort,
            MemoryPresetService memoryPresetService) {
        return new RuntimeSettingsValidator(modelSelectionService, voiceProviderCatalogPort,
                responseJsonSchemaValidatorPort, memoryPresetService);
    }

    @Bean
    RuntimeSettingsFacade runtimeSettingsFacade(
            RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            MemoryPresetService memoryPresetService,
            HiveManagedPolicyService hiveManagedPolicyService,
            RuntimeSettingsValidator validator,
            RuntimeSettingsMergeService mergeService,
            ProviderModelImportService providerModelImportService,
            ProviderModelDiscoveryService providerModelDiscoveryService) {
        return new RuntimeSettingsFacade(
                runtimeConfigService,
                preferencesService,
                memoryPresetService,
                hiveManagedPolicyService,
                validator,
                mergeService,
                providerModelImportService,
                providerModelDiscoveryService);
    }

    @Bean
    ModelSelectionCommandService modelSelectionCommandService(
            UserPreferencesService preferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeConfigService runtimeConfigService,
            SessionPort sessionPort) {
        return new ModelSelectionCommandService(preferencesService, modelSelectionService, runtimeConfigService,
                sessionPort);
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
    PlanCommandService planCommandService(PlanService planService) {
        return new PlanCommandService(planService);
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
    SessionRetentionCleanupService sessionRetentionCleanupService(
            SessionPort sessionPort,
            RuntimeConfigService runtimeConfigService,
            ActiveSessionPointerService activeSessionPointerService,
            PlanService planService,
            DelayedSessionActionService delayedSessionActionService,
            java.time.Clock clock) {
        return new SessionRetentionCleanupService(
                sessionPort,
                runtimeConfigService,
                activeSessionPointerService,
                planService,
                delayedSessionActionService,
                clock);
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
    ProviderModelImportService providerModelImportService(
            ProviderModelDiscoveryService providerModelDiscoveryService,
            ModelConfigAdminPort modelConfigAdminPort) {
        return new ProviderModelImportService(providerModelDiscoveryService, modelConfigAdminPort);
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
    SkillMarketplaceLegacySupport skillMarketplaceLegacySupport(
            SkillSettingsPort skillSettingsPort,
            StoragePort storagePort,
            SkillService skillService,
            RuntimeConfigService runtimeConfigService,
            WorkspacePathService workspacePathService,
            SkillDocumentService skillDocumentService) {
        return new SkillMarketplaceLegacySupport(
                skillSettingsPort,
                storagePort,
                skillService,
                runtimeConfigService,
                workspacePathService,
                skillDocumentService);
    }

    @Bean
    SkillMarketplaceCatalogPort skillMarketplaceCatalogPort(
            SkillMarketplaceLegacySupport skillMarketplaceLegacySupport) {
        return skillMarketplaceLegacySupport;
    }

    @Bean
    SkillMarketplaceArtifactPort skillMarketplaceArtifactPort(
            SkillMarketplaceLegacySupport skillMarketplaceLegacySupport) {
        return skillMarketplaceLegacySupport;
    }

    @Bean
    SkillMarketplaceInstallPort skillMarketplaceInstallPort(
            SkillMarketplaceLegacySupport skillMarketplaceLegacySupport) {
        return skillMarketplaceLegacySupport;
    }

    @Bean
    HiveSsoService hiveSsoService(
            RuntimeConfigService runtimeConfigService,
            HiveSessionStateStore hiveSessionStateStore,
            HiveGatewayPort hiveGatewayPort) {
        return new HiveSsoService(runtimeConfigService, hiveSessionStateStore, hiveGatewayPort);
    }

    @Bean
    SkillMarketplaceService skillMarketplaceService(
            SkillSettingsPort skillSettingsPort,
            RuntimeConfigService runtimeConfigService,
            SkillMarketplaceCatalogPort skillMarketplaceCatalogPort,
            SkillMarketplaceArtifactPort skillMarketplaceArtifactPort,
            SkillMarketplaceInstallPort skillMarketplaceInstallPort) {
        return new SkillMarketplaceService(
                skillSettingsPort,
                runtimeConfigService,
                skillMarketplaceCatalogPort,
                skillMarketplaceArtifactPort,
                skillMarketplaceInstallPort);
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
