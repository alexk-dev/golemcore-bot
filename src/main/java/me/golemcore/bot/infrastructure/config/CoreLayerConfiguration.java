package me.golemcore.bot.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import me.golemcore.bot.port.outbound.UpdateRestartPort;
import me.golemcore.bot.port.outbound.UpdateRuntimeConfigPort;
import me.golemcore.bot.port.outbound.UpdateSettingsPort;
import me.golemcore.bot.port.outbound.UpdateVersionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CoreLayerConfiguration {

    @Bean
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
                releaseSources);
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
            TraceService traceService) {
        return new AgentLoop(
                sessionService,
                rateLimiter,
                systems,
                channelRuntimePort,
                runtimeConfigService,
                preferencesService,
                llmPort,
                clock,
                traceService);
    }

    @Bean
    PlanFinalizationSystem planFinalizationSystem(
            PlanService planService,
            PlanReadyNotificationPort planReadyNotificationPort) {
        return new PlanFinalizationSystem(planService, planReadyNotificationPort);
    }
}
