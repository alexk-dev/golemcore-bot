package me.golemcore.bot.infrastructure.config;

import java.time.Clock;
import java.util.List;
import me.golemcore.bot.application.update.UpdateService;
import me.golemcore.bot.domain.service.AutoModeMigrationService;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.ContextHygieneService;
import me.golemcore.bot.domain.service.ContextTokenEstimator;
import me.golemcore.bot.domain.service.DefaultContextHygieneService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.domain.service.UpdateMaintenanceWindow;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CoreLayerConfiguration {

    @Bean
    static ContextTokenEstimator contextTokenEstimator() {
        return new ContextTokenEstimator();
    }

    @Bean
    ContextCompactionPolicy contextCompactionPolicy(
            RuntimeConfigService runtimeConfigService,
            ModelSelectionService modelSelectionService) {
        return new ContextCompactionPolicy(runtimeConfigService, modelSelectionService);
    }

    @Bean
    ScheduleService scheduleService(
            SchedulePersistencePort schedulePersistencePort,
            ScheduleCronPort scheduleCronPort,
            Clock clock,
            AutoModeMigrationService autoModeMigrationService) {
        return new ScheduleService(schedulePersistencePort, scheduleCronPort, clock, autoModeMigrationService);
    }

    ScheduleService scheduleService(
            SchedulePersistencePort schedulePersistencePort,
            ScheduleCronPort scheduleCronPort,
            Clock clock) {
        return new ScheduleService(schedulePersistencePort, scheduleCronPort, clock);
    }

    @Bean
    UpdateService updateService(
            UpdateSettingsPort settingsPort,
            UpdateVersionPort updateVersionPort,
            UpdateRuntimeConfigPort updateRuntimeConfigPort,
            UpdateRestartPort updateRestartPort,
            UpdateArtifactStorePort updateArtifactStorePort,
            Clock clock,
            UpdateActivityGate updateActivityGate,
            UpdateMaintenanceWindow updateMaintenanceWindow,
            List<ReleaseSourcePort> releaseSources) {
        return new UpdateService(
                settingsPort,
                updateVersionPort,
                updateRuntimeConfigPort,
                updateRestartPort,
                clock,
                updateActivityGate,
                updateMaintenanceWindow,
                releaseSources,
                updateArtifactStorePort);
    }

    @Bean
    AgentLoop agentLoop(
            SessionPort sessionService,
            RateLimitPort rateLimiter,
            List<AgentSystem> systems,
            ChannelRuntimePort channelRuntimePort,
            RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            LlmPort llmPort,
            Clock clock,
            TraceService traceService,
            ContextHygieneService contextHygieneService) {
        return new AgentLoop(
                sessionService,
                rateLimiter,
                systems,
                channelRuntimePort,
                runtimeConfigService,
                preferencesService,
                llmPort,
                clock,
                traceService,
                contextHygieneService);
    }

    @Bean
    ContextHygieneService contextHygieneService() {
        return new DefaultContextHygieneService();
    }

    @Bean
    PlanModeToolRestrictionService planModeToolRestrictionService(PlanService planService) {
        return new PlanModeToolRestrictionService(planService);
    }
}
