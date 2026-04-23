package me.golemcore.bot.application.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomationCommandServiceTest {

    private AutoModeService autoModeService;
    private RuntimeConfigService runtimeConfigService;
    private ScheduleService scheduleService;
    private DelayedActionPolicyService delayedActionPolicyService;
    private DelayedSessionActionService delayedSessionActionService;
    private AutomationCommandService service;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        scheduleService = mock(ScheduleService.class);
        delayedActionPolicyService = mock(DelayedActionPolicyService.class);
        delayedSessionActionService = mock(DelayedSessionActionService.class);
        service = new AutomationCommandService(
                autoModeService,
                runtimeConfigService,
                scheduleService,
                delayedActionPolicyService,
                delayedSessionActionService);
    }

    @Test
    void getAutoStatusShouldReturnUnavailableWhenFeatureDisabled() {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        AutomationCommandService.AutoOutcome outcome = service.getAutoStatus();

        assertInstanceOf(AutomationCommandService.AutoFeatureUnavailable.class, outcome);
    }

    @Test
    void createGoalShouldTranslateConfiguredGoalLimit() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(runtimeConfigService.getAutoMaxGoals()).thenReturn(3);
        when(autoModeService.createGoal("Ship release", null)).thenThrow(new IllegalStateException("full"));

        AutomationCommandService.GoalCreationOutcome outcome = service.createGoal("Ship release");

        AutomationCommandService.GoalLimitReached limit = assertInstanceOf(
                AutomationCommandService.GoalLimitReached.class,
                outcome);
        assertEquals(3, limit.maxGoals());
    }

    @Test
    void createGoalScheduleShouldRejectLegacyGoalSchedules() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        AutomationCommandService.ScheduleOutcome outcome = service.createGoalSchedule("goal-1",
                List.of("goal-1", "0", "0", "*", "*", "*"));

        AutomationCommandService.InvalidCron invalidCron = assertInstanceOf(
                AutomationCommandService.InvalidCron.class,
                outcome);
        assertEquals("Goal schedules are no longer supported", invalidCron.message());
    }

    @Test
    void createTaskScheduleShouldRejectLegacyTaskSchedules() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        AutomationCommandService.ScheduleOutcome outcome = service.createTaskSchedule("task-1",
                List.of("task-1", "0", "0", "*", "*", "*"));

        AutomationCommandService.InvalidCron invalidCron = assertInstanceOf(
                AutomationCommandService.InvalidCron.class,
                outcome);
        assertEquals("Task schedules are no longer supported", invalidCron.message());
    }

    @Test
    void listLaterActionsShouldRespectAvailabilityGuard() {
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(false);

        AutomationCommandService.LaterOutcome outcome = service.listLaterActions("telegram", "chat-1");

        assertInstanceOf(AutomationCommandService.LaterUnavailable.class, outcome);
    }
}
