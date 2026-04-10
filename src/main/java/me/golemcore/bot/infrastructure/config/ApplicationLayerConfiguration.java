package me.golemcore.bot.infrastructure.config;

import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.application.command.ModelSelectionCommandService;
import me.golemcore.bot.application.command.PlanCommandService;
import me.golemcore.bot.application.scheduler.SchedulerFacade;
import me.golemcore.bot.application.settings.RuntimeSettingsFacade;
import me.golemcore.bot.application.settings.RuntimeSettingsMergeService;
import me.golemcore.bot.application.settings.RuntimeSettingsValidator;
import me.golemcore.bot.application.skills.SkillManagementFacade;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.HiveManagedPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SkillDocumentService;
import me.golemcore.bot.domain.service.SkillMarketplaceService;
import me.golemcore.bot.domain.service.SkillService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.plugin.runtime.SttProviderRegistry;
import me.golemcore.bot.plugin.runtime.TtsProviderRegistry;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.StoragePort;
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
            SttProviderRegistry sttProviderRegistry,
            TtsProviderRegistry ttsProviderRegistry) {
        return new RuntimeSettingsValidator(modelSelectionService, sttProviderRegistry, ttsProviderRegistry);
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
