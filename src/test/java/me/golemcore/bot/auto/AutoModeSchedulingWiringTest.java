package me.golemcore.bot.auto;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.UpdateActivityGate;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.tools.GoalManagementTool;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoModeSchedulingWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AutoModeService.class, () -> mock(AutoModeService.class))
            .withBean(ScheduleService.class, () -> mock(ScheduleService.class))
            .withBean(SessionRunCoordinator.class, () -> mock(SessionRunCoordinator.class))
            .withBean(RuntimeConfigService.class, () -> {
                RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
                when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);
                when(runtimeConfigService.isAutoStartEnabled()).thenReturn(false);
                return runtimeConfigService;
            })
            .withBean(SessionPort.class, () -> mock(SessionPort.class))
            .withBean(SkillComponent.class, () -> mock(SkillComponent.class))
            .withBean(OkHttpClient.class, OkHttpClient::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ChannelRegistry.class, () -> new ChannelRegistry(List.of()))
            .withBean(GoalManagementTool.class)
            .withBean(ScheduleReportSender.class)
            .withBean(ScheduledRunMessageFactory.class)
            .withBean(ScheduledTaskShellRunner.class, () -> mock(ScheduledTaskShellRunner.class))
            .withBean(ScheduledRunExecutor.class)
            .withBean(AutoModeScheduler.class)
            .withBean(UpdateActivityGate.class);

    @Test
    void shouldCreateAutoModeSchedulingBeansFromSpringContext() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ScheduleReportSender.class);
            assertThat(context).hasSingleBean(ScheduledRunExecutor.class);
            assertThat(context).hasSingleBean(AutoModeScheduler.class);
            assertThat(context).hasSingleBean(UpdateActivityGate.class);
        });
    }
}
