package me.golemcore.bot.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

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
import org.junit.jupiter.api.Test;

class ApplicationLayerConfigurationTest {

    private final ApplicationLayerConfiguration configuration = new ApplicationLayerConfiguration();

    @Test
    void shouldCreateSettingsBeans() {
        RuntimeSettingsMergeService mergeService = configuration.runtimeSettingsMergeService();
        RuntimeSettingsValidator validator = configuration.runtimeSettingsValidator(
                mock(ModelSelectionService.class),
                mock(SttProviderRegistry.class),
                mock(TtsProviderRegistry.class));
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
        SkillManagementFacade skills = configuration.skillManagementFacade(
                mock(SkillService.class),
                new SkillDocumentService(),
                mock(SkillMarketplaceService.class),
                mock(McpPort.class),
                mock(StoragePort.class));

        assertInstanceOf(SchedulerFacade.class, scheduler);
        assertInstanceOf(SkillManagementFacade.class, skills);
    }
}
