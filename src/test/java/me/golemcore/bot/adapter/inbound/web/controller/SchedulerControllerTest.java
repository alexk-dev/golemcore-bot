package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulerControllerTest {

    private AutoModeService autoModeService;
    private ScheduleService scheduleService;
    private SchedulerController controller;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        scheduleService = mock(ScheduleService.class);
        controller = new SchedulerController(autoModeService, scheduleService);
    }

    @Test
    void getStateShouldReturnGoalsAndSchedulesWithResolvedLabels() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Release v1")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(AutoTask.builder()
                        .id("task-1")
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
                .maxExecutions(3)
                .executionCount(1)
                .createdAt(Instant.parse("2026-03-01T01:00:00Z"))
                .updatedAt(Instant.parse("2026-03-01T01:00:00Z"))
                .build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(scheduleService.getSchedules()).thenReturn(List.of(entry));

        StepVerifier.create(controller.getState())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SchedulerController.SchedulerStateResponse body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.featureEnabled());
                    assertTrue(body.autoModeEnabled());
                    assertEquals(1, body.goals().size());
                    assertEquals(1, body.schedules().size());
                    assertEquals("Prepare notes", body.schedules().get(0).targetLabel());
                })
                .verifyComplete();
    }

    @Test
    void createScheduleShouldRejectWhenFeatureDisabled() {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        SchedulerController.CreateScheduleRequest request = new SchedulerController.CreateScheduleRequest(
                "GOAL",
                "goal-1",
                "daily",
                List.of(),
                "09:00",
                null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createSchedule(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void createScheduleShouldCreateGoalDailySchedule() {
        Goal goal = Goal.builder().id("goal-1").title("Goal").build();
        ScheduleEntry created = ScheduleEntry.builder()
                .id("sched-goal-1")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .targetId("goal-1")
                .cronExpression("0 0 9 * * *")
                .enabled(true)
                .maxExecutions(5)
                .executionCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal("goal-1")).thenReturn(Optional.of(goal));
        when(autoModeService.getGoals()).thenReturn(List.of(goal));
        when(scheduleService.createSchedule(eq(ScheduleEntry.ScheduleType.GOAL), eq("goal-1"), eq("0 0 9 * * *"),
                eq(5)))
                .thenReturn(created);

        SchedulerController.CreateScheduleRequest request = new SchedulerController.CreateScheduleRequest(
                "GOAL",
                "goal-1",
                "daily",
                List.of(),
                "09:00",
                5);

        StepVerifier.create(controller.createSchedule(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    SchedulerController.ScheduleDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("sched-goal-1", body.id());
                    assertEquals("Goal", body.targetLabel());
                    assertEquals(5, body.maxExecutions());
                })
                .verifyComplete();
    }

    @Test
    void createScheduleShouldSupportHhmmFormatAndUnlimitedLimit() {
        Goal goal = Goal.builder().id("goal-1").title("Goal").build();
        AutoTask task = AutoTask.builder().id("task-1").title("Task").order(1).build();
        Goal taskGoal = Goal.builder().id("goal-2").title("Goal 2").tasks(List.of(task)).build();
        ScheduleEntry created = ScheduleEntry.builder()
                .id("sched-task-1")
                .type(ScheduleEntry.ScheduleType.TASK)
                .targetId("task-1")
                .cronExpression("0 30 18 * * MON,THU")
                .enabled(true)
                .maxExecutions(-1)
                .executionCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.findGoalForTask("task-1")).thenReturn(Optional.of(taskGoal));
        when(autoModeService.getGoals()).thenReturn(List.of(goal, taskGoal));
        when(scheduleService.createSchedule(eq(ScheduleEntry.ScheduleType.TASK), eq("task-1"),
                eq("0 30 18 * * MON,THU"), eq(-1)))
                .thenReturn(created);

        SchedulerController.CreateScheduleRequest request = new SchedulerController.CreateScheduleRequest(
                "TASK",
                "task-1",
                "custom",
                List.of(1, 4),
                "1830",
                0);

        StepVerifier.create(controller.createSchedule(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    SchedulerController.ScheduleDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("Task", body.targetLabel());
                    assertEquals(-1, body.maxExecutions());
                })
                .verifyComplete();
    }

    @Test
    void createScheduleShouldRejectInvalidWeeklyDays() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoal("goal-1"))
                .thenReturn(Optional.of(Goal.builder().id("goal-1").title("Goal").build()));

        SchedulerController.CreateScheduleRequest request = new SchedulerController.CreateScheduleRequest(
                "GOAL",
                "goal-1",
                "weekly",
                List.of(9),
                "09:00",
                1);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createSchedule(request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason() != null && exception.getReason().contains("Weekday"));
    }

    @Test
    void deleteScheduleShouldDeleteById() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        StepVerifier.create(controller.deleteSchedule("sched-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    SchedulerController.DeleteScheduleResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals("sched-1", body.scheduleId());
                })
                .verifyComplete();

        verify(scheduleService).deleteSchedule("sched-1");
    }

    @Test
    void deleteScheduleShouldRejectBlankId() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSchedule("  "));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
