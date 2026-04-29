package me.golemcore.bot.domain.session;

import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionScopedGoalServiceTest {

    private static final String SESSION_ID = "session-1";

    private SessionGoalStorageService goalStorageService;
    private RuntimeConfigService runtimeConfigService;
    private SessionDiaryService diaryService;
    private SessionScopedGoalService service;

    @BeforeEach
    void setUp() {
        goalStorageService = mock(SessionGoalStorageService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        diaryService = mock(SessionDiaryService.class);
        when(runtimeConfigService.getAutoReflectionFailureThreshold()).thenReturn(2);
        service = new SessionScopedGoalService(goalStorageService, runtimeConfigService, diaryService);
    }

    @Test
    void buildAutoContextShouldKeepInstructionsAndTruncateLongFields() {
        String longGoalPrompt = "goal prompt ".repeat(160);
        String longTaskDetails = "task details ".repeat(160);
        AutoTask currentTask = AutoTask.builder()
                .id("task-1")
                .goalId("goal-1")
                .title("Investigate billing sync")
                .description(longTaskDetails)
                .prompt("Use ledger reconciliation")
                .reflectionModelTier("deep")
                .reflectionTierPriority(true)
                .reflectionStrategy("Retry with smaller date windows")
                .reflectionRequired(true)
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        Goal currentGoal = goal("goal-1", "Billing reliability", Instant.parse("2026-01-01T00:00:00Z"));
        currentGoal.setPrompt(longGoalPrompt);
        currentGoal.setReflectionModelTier("analysis");
        currentGoal.setReflectionTierPriority(false);
        currentGoal.setReflectionStrategy("Prefer read-only diagnostics first");
        currentGoal.setTasks(new ArrayList<>(List.of(currentTask)));
        Goal nullCreatedGoal = goal("goal-null-created", "Untimed active goal", null);
        when(goalStorageService.loadGoals(SESSION_ID)).thenReturn(new ArrayList<>(List.of(
                currentGoal,
                goal("goal-2", "Sync receipts", Instant.parse("2026-01-02T00:00:00Z")),
                goal("goal-3", "Billing dashboard", Instant.parse("2026-01-03T00:00:00Z")),
                goal("goal-4", "Ledger alerts", Instant.parse("2026-01-04T00:00:00Z")),
                goal("goal-5", "Ops cleanup", Instant.parse("2026-01-05T00:00:00Z")),
                nullCreatedGoal)));
        when(diaryService.getRecentDiary(eq(SESSION_ID), eq(20))).thenReturn(List.of(
                diary("goal-1", "task-1", "Earlier task note"),
                diary("goal-2", null, "Receipt sync note"),
                diary(null, null, "unrelated but recent"),
                diary("goal-1", "task-1", "billing sync ".repeat(50))));

        String context = service.buildAutoContext(SESSION_ID, null, "task-1");

        assertTrue(context.contains("# Auto Mode"));
        assertTrue(context.contains("## Current Goal"));
        assertTrue(context.contains("Billing reliability"));
        assertTrue(context.contains("## Current Task"));
        assertTrue(context.contains("Investigate billing sync"));
        assertTrue(context.contains("Reflection tier: analysis (default)"));
        assertTrue(context.contains("Reflection tier: deep (priority)"));
        assertTrue(context.contains("Reflection required after repeated failures."));
        assertTrue(context.contains(" ... [truncated]"));
        assertTrue(context.contains("## Other Active Goals (summary)"));
        assertTrue(context.contains("more active goals omitted from prompt"));
        assertTrue(context.contains("## Relevant Diary"));
        assertTrue(context.contains("Older or unrelated diary entries are intentionally omitted"));
        assertTrue(context.contains("## Instructions"));
        assertTrue(context.contains("You are in autonomous work mode."));
        assertFalse(context.contains(longGoalPrompt));
        assertFalse(context.contains(longTaskDetails));
    }

    @Test
    void buildAutoContextShouldReturnNullWhenThereAreNoActiveGoals() {
        when(goalStorageService.loadGoals(SESSION_ID)).thenReturn(new ArrayList<>(List.of(
                Goal.builder()
                        .id("goal-1")
                        .title("Done")
                        .status(Goal.GoalStatus.COMPLETED)
                        .tasks(new ArrayList<>())
                        .build())));

        assertNull(service.buildAutoContext(SESSION_ID));
    }

    @Test
    void goalAndTaskLifecycleShouldPreserveDiaryAndReflectionBehavior() {
        AutoTask task = AutoTask.builder()
                .id("task-1")
                .goalId("goal-1")
                .title("Ship fix")
                .status(AutoTask.TaskStatus.PENDING)
                .order(2)
                .createdAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();
        Goal goal = goal("goal-1", "Release", Instant.parse("2026-01-01T00:00:00Z"));
        goal.setTasks(new ArrayList<>(List.of(task)));
        when(goalStorageService.loadGoals(SESSION_ID)).thenReturn(new ArrayList<>(List.of(goal)));

        service.recordAutoRunFailure(SESSION_ID, "goal-1", null, " goal failure ", null, "planner");
        service.recordAutoRunFailure(SESSION_ID, "goal-1", null, " another failure ", " explicit ", "planner");
        assertTrue(goal.isReflectionRequired());
        assertEquals("explicit", goal.getLastFailureFingerprint());

        service.applyReflectionResult(SESSION_ID, "goal-1", null, " Use narrower scope ");
        assertFalse(goal.isReflectionRequired());
        assertEquals("Use narrower scope", goal.getReflectionStrategy());

        service.recordAutoRunFailure(SESSION_ID, "goal-1", "task-1", " task failed ", null, "builder");
        service.recordAutoRunFailure(SESSION_ID, "goal-1", "task-1", " task failed again ", " task-explicit ",
                "builder");
        assertEquals(AutoTask.TaskStatus.FAILED, task.getStatus());
        assertTrue(task.isReflectionRequired());
        assertEquals("task-explicit", task.getLastFailureFingerprint());

        service.recordAutoRunSuccess(SESSION_ID, "goal-1", "task-1", "builder");
        assertEquals(AutoTask.TaskStatus.IN_PROGRESS, task.getStatus());
        assertFalse(task.isReflectionRequired());
        assertEquals("builder", task.getLastUsedSkillName());

        service.updateTaskStatus(SESSION_ID, "goal-1", "task-1", AutoTask.TaskStatus.COMPLETED,
                "verified in staging");
        assertEquals(AutoTask.TaskStatus.COMPLETED, task.getStatus());
        assertEquals("verified in staging", task.getResult());

        service.deleteGoal(SESSION_ID, "goal-1");

        ArgumentCaptor<DiaryEntry> diaryCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryService, atLeastOnce()).writeDiary(eq(SESSION_ID), diaryCaptor.capture());
        List<DiaryEntry> entries = diaryCaptor.getAllValues();
        assertTrue(entries.stream().anyMatch(entry -> "Completed task: Ship fix - verified in staging"
                .equals(entry.getContent()) && entry.getType() == DiaryEntry.DiaryType.PROGRESS));
        assertTrue(entries.stream().anyMatch(entry -> "Goal deleted: goal-1".equals(entry.getContent())
                && entry.getType() == DiaryEntry.DiaryType.DECISION));
        verify(goalStorageService, atLeastOnce()).saveGoals(eq(SESSION_ID), any());
    }

    @Test
    void createGoalShouldNotLimitActiveGoalCount() {
        List<Goal> goals = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            goals.add(goal("goal-" + i, "Goal " + i, Instant.parse("2026-01-01T00:00:00Z")));
        }
        when(goalStorageService.loadGoals(SESSION_ID)).thenReturn(goals);

        Goal created = service.createGoal(SESSION_ID, "Goal 6", null, null, null, false);

        assertEquals("Goal 6", created.getTitle());
        assertEquals(6, goals.size());
        verify(goalStorageService).saveGoals(eq(SESSION_ID), any());
    }

    @Test
    void createTaskShouldNotLimitTaskCountPerGoal() {
        Goal goal = goal("goal-1", "Release", Instant.parse("2026-01-01T00:00:00Z"));
        for (int i = 1; i <= 20; i++) {
            goal.getTasks().add(AutoTask.builder()
                    .id("task-" + i)
                    .goalId("goal-1")
                    .title("Task " + i)
                    .status(AutoTask.TaskStatus.PENDING)
                    .order(i)
                    .build());
        }
        when(goalStorageService.loadGoals(SESSION_ID)).thenReturn(new ArrayList<>(List.of(goal)));

        AutoTask created = service.createTask(SESSION_ID, "goal-1", "Task 21", null, null, null, false,
                AutoTask.TaskStatus.PENDING);

        assertEquals("Task 21", created.getTitle());
        assertEquals(21, goal.getTasks().size());
        assertEquals(21, created.getOrder());
        verify(goalStorageService).saveGoals(eq(SESSION_ID), any());
    }

    private static Goal goal(String id, String title, Instant createdAt) {
        return Goal.builder()
                .id(id)
                .title(title)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private static DiaryEntry diary(String goalId, String taskId, String content) {
        return DiaryEntry.builder()
                .timestamp(Instant.parse("2026-01-01T12:00:00Z"))
                .type(DiaryEntry.DiaryType.PROGRESS)
                .goalId(goalId)
                .taskId(taskId)
                .content(content)
                .build();
    }
}
