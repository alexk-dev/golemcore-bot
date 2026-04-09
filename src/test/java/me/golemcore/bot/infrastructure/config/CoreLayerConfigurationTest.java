package me.golemcore.bot.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.List;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.domain.service.UpdateMaintenanceWindow;
import me.golemcore.bot.domain.service.UpdateService;
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
}
