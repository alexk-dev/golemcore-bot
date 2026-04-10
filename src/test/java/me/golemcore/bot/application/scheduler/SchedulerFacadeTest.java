package me.golemcore.bot.application.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchedulerFacadeTest {

    private AutoModeService autoModeService;
    private ScheduleService scheduleService;
    private RuntimeConfigService runtimeConfigService;
    private ChannelRuntimePort channelRuntimePort;
    private SchedulerFacade facade;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        scheduleService = mock(ScheduleService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        channelRuntimePort = mock(ChannelRuntimePort.class);
        facade = new SchedulerFacade(autoModeService, scheduleService, runtimeConfigService, channelRuntimePort);

        RuntimeConfig.TelegramConfig telegramConfig = new RuntimeConfig.TelegramConfig();
        telegramConfig.setAllowedUsers(List.of());
        when(runtimeConfigService.getRuntimeConfig())
                .thenReturn(RuntimeConfig.builder().telegram(telegramConfig).build());
    }

    @Test
    void getStateShouldAssembleGoalsSchedulesAndReportChannels() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Release v1")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id("task-1")
                        .goalId("goal-1")
                        .title("Prepare notes")
                        .status(AutoTask.TaskStatus.PENDING)
                        .order(1)
                        .build()))
                .createdAt(Instant.parse("2026-03-01T00:00:00Z"))
                .build();
        ScheduleEntry entry = ScheduleEntry.builder()
                .id("sched-task-1")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-1")
                .cronExpression("0 0 9 * * MON")
                .enabled(true)
                .createdAt(Instant.parse("2026-03-01T01:00:00Z"))
                .updatedAt(Instant.parse("2026-03-01T01:00:00Z"))
                .build();
        ChannelPort webhookChannel = mock(ChannelPort.class);
        when(webhookChannel.getChannelType()).thenReturn("webhook");

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(autoModeService.isInboxGoal(goal)).thenReturn(false);
        when(scheduleService.getSchedules()).thenReturn(List.of(entry));
        when(channelRuntimePort.listChannels()).thenReturn(List.of(webhookChannel));

        SchedulerFacade.SchedulerStateView state = facade.getState();

        assertEquals(1, state.goals().size());
        assertEquals(1, state.schedules().size());
        assertEquals("Prepare notes", state.schedules().getFirst().targetLabel());
        assertEquals("webhook", state.reportChannelOptions().getFirst().type());
    }

    @Test
    void createScheduleShouldRejectWhenFeatureDisabled() {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> facade.createSchedule(new SchedulerFacade.CreateScheduleCommand(
                        "GOAL",
                        "goal-1",
                        "daily",
                        List.of(),
                        "09:00",
                        null,
                        null,
                        null,
                        null,
                        null)));

        assertEquals("Auto mode feature is disabled", exception.getMessage());
    }

    @Test
    void createScheduleShouldTranslateReportRequestIntoDomainCall() {
        Goal goal = Goal.builder().id("goal-1").title("Goal").build();
        ScheduleEntry created = ScheduleEntry.builder()
                .id("sched-goal-1")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .cronExpression("0 0 9 * * *")
                .enabled(true)
                .report(ScheduleReportConfig.builder().channelType("webhook").webhookUrl("https://hook").build())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal("goal-1")).thenReturn(Optional.of(goal));
        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(scheduleService.createSchedule(
                eq(ScheduleEntry.ScheduleType.GOAL),
                eq("goal-1"),
                eq("0 0 9 * * *"),
                eq(-1),
                eq(true),
                any(ScheduleReportConfig.class))).thenReturn(created);

        SchedulerFacade.ScheduleView result = facade.createSchedule(new SchedulerFacade.CreateScheduleCommand(
                "goal",
                "goal-1",
                "daily",
                List.of(),
                "09:00",
                null,
                null,
                null,
                true,
                new SchedulerFacade.ScheduleReportRequest("webhook", null, "https://hook", null)));

        assertEquals("sched-goal-1", result.id());
        assertEquals("Goal", result.targetLabel());
        assertEquals("webhook", result.report().channelType());
    }
}
