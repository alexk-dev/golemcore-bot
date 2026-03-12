package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.service.AutoModeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalsControllerTest {

    private AutoModeService autoModeService;
    private GoalsController controller;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        controller = new GoalsController(autoModeService);
    }

    @Test
    void getGoalsShouldSplitStandaloneTasksFromVisibleGoals() {
        AutoTask goalTask = AutoTask.builder()
                .id("task-goal-1")
                .goalId("goal-1")
                .title("Prepare changelog")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Release v2")
                .prompt("Ship release")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(goalTask))
                .build();

        AutoTask standaloneTask = AutoTask.builder()
                .id("task-standalone-1")
                .goalId("inbox")
                .title("Investigate flaky test")
                .status(AutoTask.TaskStatus.IN_PROGRESS)
                .order(1)
                .build();
        Goal inbox = Goal.builder()
                .id("inbox")
                .title("Inbox")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of(standaloneTask))
                .build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(goal, inbox));
        when(autoModeService.isInboxGoal(goal)).thenReturn(false);
        when(autoModeService.isInboxGoal(inbox)).thenReturn(true);

        StepVerifier.create(controller.getGoals())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    GoalsController.GoalsResponse body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.goals().size());
                    assertEquals(1, body.standaloneTasks().size());
                    assertEquals("Release v2", body.goals().get(0).title());
                    assertTrue(body.standaloneTasks().get(0).standalone());
                    assertEquals("Investigate flaky test", body.standaloneTasks().get(0).title());
                })
                .verifyComplete();
    }

    @Test
    void createGoalShouldReturnCreatedGoalWithPrompt() {
        Goal goal = Goal.builder()
                .id("goal-1")
                .title("Release v2")
                .description("Prepare release train")
                .prompt("Ship version 2 with release checklist")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(List.of())
                .build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createGoal("Release v2", "Prepare release train", "Ship version 2 with release checklist"))
                .thenReturn(goal);

        GoalsController.CreateGoalRequest request = new GoalsController.CreateGoalRequest(
                "Release v2",
                "Prepare release train",
                "Ship version 2 with release checklist");

        StepVerifier.create(controller.createGoal(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    GoalsController.GoalDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("Release v2", body.title());
                    assertEquals("Ship version 2 with release checklist", body.prompt());
                })
                .verifyComplete();
    }

    @Test
    void createTaskShouldReturnStandaloneTaskWhenInboxGoalOwnsIt() {
        AutoTask task = AutoTask.builder()
                .id("task-1")
                .goalId("inbox")
                .title("Review crash logs")
                .prompt("Inspect the latest crash logs and summarize issues")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .build();
        Goal inbox = Goal.builder().id("inbox").title("Inbox").build();

        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createTask(
                null,
                "Review crash logs",
                "Check prod errors",
                "Inspect the latest crash logs and summarize issues",
                AutoTask.TaskStatus.PENDING))
                .thenReturn(task);
        when(autoModeService.findGoalForTask("task-1")).thenReturn(Optional.of(inbox));
        when(autoModeService.isInboxGoal(inbox)).thenReturn(true);

        GoalsController.CreateTaskRequest request = new GoalsController.CreateTaskRequest(
                null,
                "Review crash logs",
                "Check prod errors",
                "Inspect the latest crash logs and summarize issues",
                null);

        StepVerifier.create(controller.createTask(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    GoalsController.TaskDto body = response.getBody();
                    assertNotNull(body);
                    assertTrue(body.standalone());
                    assertEquals("Review crash logs", body.title());
                    assertNull(body.goalId());
                })
                .verifyComplete();
    }

    @Test
    void updateGoalShouldRejectWhenFeatureDisabled() {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        GoalsController.UpdateGoalRequest request = new GoalsController.UpdateGoalRequest(
                "Release v2",
                "Prepare release train",
                "Ship version 2 with release checklist",
                "ACTIVE");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.updateGoal("goal-1", request));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }
}
