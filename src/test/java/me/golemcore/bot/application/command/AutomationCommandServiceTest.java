package me.golemcore.bot.application.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedActionStatus;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
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
    void sessionScopedReadMethodsShouldDelegateToExplicitSessionWhenProvided() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Ship release")
                .tasks(List.of(AutoTask.builder().id("task-1").title("Test").build()))
                .build();
        AutoTask task = goal.getTasks().getFirst();
        DiaryEntry entry = DiaryEntry.builder()
                .timestamp(Instant.parse("2026-01-01T00:00:00Z"))
                .content("Done")
                .build();
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("schedule-1")
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId("scheduled-task-1")
                .build();
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getActiveGoals()).thenReturn(List.of(goal));
        when(autoModeService.getActiveGoals("session-1")).thenReturn(List.of(goal));
        when(autoModeService.getNextPendingTask()).thenReturn(Optional.of(task));
        when(autoModeService.getNextPendingTask("session-1")).thenReturn(Optional.empty());
        when(autoModeService.getGoals("session-1")).thenReturn(List.of(goal));
        when(autoModeService.getRecentDiary("session-1", 5)).thenReturn(List.of(entry));
        when(scheduleService.getSchedules()).thenReturn(List.of(schedule));

        assertEquals(List.of(goal), service.getActiveGoals(null));
        assertEquals(List.of(goal), service.getActiveGoals("session-1"));
        assertEquals(Optional.of(task), service.getNextPendingTask(" "));
        assertEquals(Optional.empty(), service.getNextPendingTask("session-1"));
        assertInstanceOf(AutomationCommandService.GoalsOverview.class, service.getGoals("session-1"));
        assertInstanceOf(AutomationCommandService.TasksOverview.class, service.getTasks("session-1"));
        assertInstanceOf(AutomationCommandService.DiaryOverview.class, service.getDiary("session-1", 5));
        assertInstanceOf(AutomationCommandService.SchedulesOverview.class, service.listSchedules());
    }

    @Test
    void createGoalAndDeleteScheduleShouldReturnUsageSuccessAndNotFoundOutcomes() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Ship release")
                .build();
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createGoal("session-1", "Ship release", null, null, null, false)).thenReturn(goal);
        doThrow(new IllegalArgumentException("missing")).when(scheduleService).deleteSchedule("missing");

        assertInstanceOf(AutomationCommandService.GoalDescriptionRequired.class, service.createGoal("session-1", " "));
        AutomationCommandService.GoalCreated created = assertInstanceOf(
                AutomationCommandService.GoalCreated.class,
                service.createGoal("session-1", "Ship release"));
        assertEquals("goal-1", created.goal().getId());
        assertInstanceOf(AutomationCommandService.DeleteScheduleUsage.class, service.deleteSchedule(" "));
        assertInstanceOf(AutomationCommandService.ScheduleNotFound.class, service.deleteSchedule("missing"));
        assertInstanceOf(AutomationCommandService.ScheduleDeleted.class, service.deleteSchedule("schedule-1"));
        verify(scheduleService).deleteSchedule("schedule-1");
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

    @Test
    void laterActionsShouldCoverEmptyOverviewCancelAndRunNowOutcomes() {
        DelayedSessionAction action = DelayedSessionAction.builder()
                .id("later-1")
                .kind(DelayedActionKind.REMIND_LATER)
                .status(DelayedActionStatus.SCHEDULED)
                .runAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(runtimeConfigService.isDelayedActionsEnabled()).thenReturn(true);
        when(delayedActionPolicyService.canScheduleActions("telegram")).thenReturn(true);
        when(delayedSessionActionService.listActions("telegram", "chat-1"))
                .thenReturn(List.of(), List.of(action));
        when(delayedSessionActionService.cancelAction("later-1", "telegram", "chat-1")).thenReturn(true);
        when(delayedSessionActionService.runNow("later-1", "telegram", "chat-1")).thenReturn(true);

        assertInstanceOf(AutomationCommandService.EmptyLaterActions.class,
                service.listLaterActions("telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterActionsOverview.class,
                service.listLaterActions("telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterCancelUsage.class,
                service.cancelLaterAction(" ", "telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterNotFound.class,
                service.cancelLaterAction("missing", "telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterCancelled.class,
                service.cancelLaterAction("later-1", "telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterRunNowUsage.class,
                service.runLaterActionNow(null, "telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterNotFound.class,
                service.runLaterActionNow("missing", "telegram", "chat-1"));
        assertInstanceOf(AutomationCommandService.LaterRunNow.class,
                service.runLaterActionNow("later-1", "telegram", "chat-1"));
        assertEquals(true, service.canScheduleActions("telegram"));
    }
}
