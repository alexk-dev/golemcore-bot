package me.golemcore.bot.infrastructure.config;

import java.time.Clock;
import java.util.List;
import me.golemcore.bot.application.update.UpdateService;
import me.golemcore.bot.domain.auto.AutoModeMigrationService;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.loop.AgentLoopFactory;
import me.golemcore.bot.domain.loop.AgentLoopFactory.AgentLoopPorts;
import me.golemcore.bot.domain.loop.AgentLoopFactory.AgentLoopRuntimeServices;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.context.hygiene.ContextHygieneService;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import me.golemcore.bot.domain.context.hygiene.DefaultContextHygieneService;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.tools.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.planning.PlanService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.scheduling.ScheduleService;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.update.UpdateActivityGate;
import me.golemcore.bot.domain.update.UpdateMaintenanceWindow;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.PlanExecutionContextCleanupSystem;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.ReleaseSourcePort;
import me.golemcore.bot.port.outbound.ScheduleCronPort;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
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
            TraceSnapshotCodecPort traceSnapshotCodecPort,
            ContextHygieneService contextHygieneService,
            RuntimeEventService runtimeEventService) {
        return new AgentLoopFactory().create(
                new AgentLoopPorts(sessionService, rateLimiter, channelRuntimePort, llmPort),
                new AgentLoopRuntimeServices(runtimeConfigService, preferencesService, clock, traceService,
                        traceSnapshotCodecPort, contextHygieneService, runtimeEventService),
                systems);
    }

    @Bean
    ContextHygieneService contextHygieneService() {
        return new DefaultContextHygieneService();
    }

    @Bean
    PlanModeToolRestrictionService planModeToolRestrictionService(PlanService planService) {
        return new PlanModeToolRestrictionService(planService);
    }

    @Bean
    PlanExecutionContextCleanupSystem planExecutionContextCleanupSystem(PlanService planService) {
        return new PlanExecutionContextCleanupSystem(planService);
    }
}
