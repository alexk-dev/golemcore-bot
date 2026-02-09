package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalManagementToolTest {

    private AutoModeService autoModeService;
    private GoalManagementTool tool;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);

        BotProperties properties = new BotProperties();
        properties.getTools().getGoalManagement().setEnabled(true);

        tool = new GoalManagementTool(properties, autoModeService);
    }

    @Test
    void createGoalSuccess() throws Exception {
        Goal goal = Goal.builder()
                .id("g1")
                .title("Learn Java")
                .description("Study Java fundamentals")
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(autoModeService.createGoal("Learn Java", "Study Java fundamentals"))
                .thenReturn(goal);

        ToolResult result = tool.execute(Map.of(
                "operation", "create_goal",
                "title", "Learn Java",
                "description", "Study Java fundamentals")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Learn Java"));
        assertTrue(result.getOutput().contains("g1"));
        verify(autoModeService).createGoal("Learn Java", "Study Java fundamentals");
    }

    @Test
    void createGoalMissingTitle() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create_goal")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("title"));
        verifyNoInteractions(autoModeService);
    }

    @Test
    void createGoalLimitReached() throws Exception {
        when(autoModeService.createGoal(anyString(), any()))
                .thenThrow(new IllegalStateException("Maximum active goals reached: 3"));

        ToolResult result = tool.execute(Map.of(
                "operation", "create_goal",
                "title", "Too many goals")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Maximum active goals reached"));
    }

    @Test
    void listGoalsWithGoals() throws Exception {
        AutoTask task1 = AutoTask.builder()
                .id("t1").goalId("g1").title("Task 1")
                .status(AutoTask.TaskStatus.COMPLETED)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        AutoTask task2 = AutoTask.builder()
                .id("t2").goalId("g1").title("Task 2")
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        List<AutoTask> tasks = new ArrayList<>();
        tasks.add(task1);
        tasks.add(task2);

        Goal goal = Goal.builder()
                .id("g1").title("Learn Java")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(tasks)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.getGoals()).thenReturn(List.of(goal));

        ToolResult result = tool.execute(Map.of(
                "operation", "list_goals")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Learn Java"));
        assertTrue(result.getOutput().contains("1/2"));
        assertTrue(result.getOutput().contains("1"));
    }

    @Test
    void listGoalsEmpty() throws Exception {
        when(autoModeService.getGoals()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of(
                "operation", "list_goals")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No goals"));
    }

    @Test
    void planTasksSuccess() throws Exception {
        AutoTask task1 = AutoTask.builder()
                .id("t1").goalId("g1").title("Read chapter 1")
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        AutoTask task2 = AutoTask.builder()
                .id("t2").goalId("g1").title("Do exercises")
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.addTask("g1", "Read chapter 1", "Read the first chapter", 1))
                .thenReturn(task1);
        when(autoModeService.addTask("g1", "Do exercises", "Complete exercises", 2))
                .thenReturn(task2);

        List<Map<String, Object>> tasksList = List.of(
                Map.of("title", "Read chapter 1", "description", "Read the first chapter"),
                Map.of("title", "Do exercises", "description", "Complete exercises"));

        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("goal_id", "g1");
        params.put("tasks", tasksList);

        ToolResult result = tool.execute(params).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Read chapter 1"));
        assertTrue(result.getOutput().contains("Do exercises"));
        verify(autoModeService).addTask("g1", "Read chapter 1", "Read the first chapter", 1);
        verify(autoModeService).addTask("g1", "Do exercises", "Complete exercises", 2);
    }

    @Test
    void planTasksMissingGoalId() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("tasks", List.of(Map.of("title", "Task 1")));

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("goal_id"));
        verifyNoInteractions(autoModeService);
    }

    @Test
    void updateTaskStatusSuccess() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "update_task_status",
                "goal_id", "g1",
                "task_id", "t1",
                "status", "IN_PROGRESS")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("IN_PROGRESS"));
        verify(autoModeService).updateTaskStatus("g1", "t1",
                AutoTask.TaskStatus.IN_PROGRESS, null);
    }

    @Test
    void updateTaskStatusInvalidStatus() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "update_task_status",
                "goal_id", "g1",
                "task_id", "t1",
                "status", "BANANA")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid status"));
        assertTrue(result.getError().contains("BANANA"));
        verifyNoInteractions(autoModeService);
    }

    @Test
    void writeDiarySuccess() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "write_diary",
                "content", "Made good progress today")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Diary entry written"));
        verify(autoModeService).writeDiary(any(DiaryEntry.class));
    }

    @Test
    void completeGoalSuccess() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "complete_goal",
                "goal_id", "g1")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("completed"));
        verify(autoModeService).completeGoal("g1");
    }

    @Test
    void disabledTool() throws Exception {
        BotProperties props = new BotProperties();
        props.getTools().getGoalManagement().setEnabled(false);
        GoalManagementTool disabledTool = new GoalManagementTool(props, autoModeService);

        ToolResult result = disabledTool.execute(Map.of(
                "operation", "list_goals")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("disabled"));
    }

    @Test
    void missingOperation() throws Exception {
        ToolResult result = tool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("operation"));
    }

    // ===== Unknown operation =====

    @Test
    void unknownOperation() throws Exception {
        ToolResult result = tool.execute(Map.of("operation", "delete_everything")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    // ===== planTasks edge cases =====

    @Test
    void planTasksMissingTasks() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("goal_id", "g1");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("tasks"));
    }

    @Test
    void planTasksEmptyList() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("goal_id", "g1");
        params.put("tasks", List.of());

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("empty"));
    }

    @Test
    void planTasksWithMissingTitle() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("goal_id", "g1");
        params.put("tasks", List.of(Map.of("description", "No title")));

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("missing title"));
    }

    @Test
    void planTasksInvalidTasksType() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("goal_id", "g1");
        params.put("tasks", "not a list");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("must be an array"));
    }

    // ===== updateTaskStatus edge cases =====

    @Test
    void updateTaskStatusMissingTaskId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "update_task_status",
                "goal_id", "g1",
                "status", "COMPLETED")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("task_id"));
    }

    @Test
    void updateTaskStatusMissingStatus() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "update_task_status",
                "goal_id", "g1",
                "task_id", "t1")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("status"));
    }

    @Test
    void updateTaskStatusMissingGoalId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "update_task_status",
                "task_id", "t1",
                "status", "COMPLETED")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("goal_id"));
    }

    // ===== milestone callback =====

    @Test
    void updateTaskStatusCompletedTriggersCallback() throws Exception {
        java.util.concurrent.atomic.AtomicReference<GoalManagementTool.MilestoneEvent> event = new java.util.concurrent.atomic.AtomicReference<>();
        tool.setMilestoneCallback(event::set);

        AutoTask task = AutoTask.builder()
                .id("t1").goalId("g1").title("Test task")
                .status(AutoTask.TaskStatus.COMPLETED)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        Goal goal = Goal.builder()
                .id("g1").title("Test Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.getGoal("g1")).thenReturn(java.util.Optional.of(goal));

        ToolResult result = tool.execute(Map.of(
                "operation", "update_task_status",
                "goal_id", "g1",
                "task_id", "t1",
                "status", "COMPLETED")).get();

        assertTrue(result.isSuccess());
        assertNotNull(event.get());
        assertTrue(event.get().message().contains("Task completed"));
    }

    @Test
    void completeGoalTriggersCallback() throws Exception {
        java.util.concurrent.atomic.AtomicReference<GoalManagementTool.MilestoneEvent> event = new java.util.concurrent.atomic.AtomicReference<>();
        tool.setMilestoneCallback(event::set);

        Goal goal = Goal.builder()
                .id("g1").title("Completed Goal")
                .status(Goal.GoalStatus.COMPLETED)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.getGoal("g1")).thenReturn(java.util.Optional.of(goal));

        ToolResult result = tool.execute(Map.of(
                "operation", "complete_goal",
                "goal_id", "g1")).get();

        assertTrue(result.isSuccess());
        assertNotNull(event.get());
        assertTrue(event.get().message().contains("Goal completed"));
    }

    // ===== writeDiary edge cases =====

    @Test
    void writeDiaryMissingContent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "write_diary")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("content"));
    }

    @Test
    void writeDiaryWithType() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "write_diary",
                "content", "Progress update",
                "diary_type", "PROGRESS")).get();

        assertTrue(result.isSuccess());
        verify(autoModeService).writeDiary(argThat(entry -> entry.getType() == DiaryEntry.DiaryType.PROGRESS));
    }

    @Test
    void writeDiaryWithInvalidType() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "write_diary",
                "content", "Some note",
                "diary_type", "INVALID_TYPE")).get();

        assertTrue(result.isSuccess()); // Should default to THOUGHT
        verify(autoModeService).writeDiary(argThat(entry -> entry.getType() == DiaryEntry.DiaryType.THOUGHT));
    }

    @Test
    void writeDiaryWithGoalAndTaskId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "write_diary",
                "content", "Working on task",
                "goal_id", "g1",
                "task_id", "t1")).get();

        assertTrue(result.isSuccess());
        verify(autoModeService)
                .writeDiary(argThat(entry -> "g1".equals(entry.getGoalId()) && "t1".equals(entry.getTaskId())));
    }

    // ===== completeGoal edge cases =====

    @Test
    void completeGoalMissingGoalId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "complete_goal")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("goal_id"));
    }

    // ===== createGoal with blank title =====

    @Test
    void createGoalBlankTitle() throws Exception {
        ToolResult result = tool.execute(Map.of(
                "operation", "create_goal",
                "title", "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("title"));
    }

    // ===== getDefinition =====

    @Test
    void shouldReturnValidDefinition() {
        assertNotNull(tool.getDefinition());
        assertEquals("goal_management", tool.getDefinition().getName());
        assertNotNull(tool.getDefinition().getInputSchema());
    }

    // ===== isEnabled =====

    @Test
    void shouldBeEnabled() {
        assertTrue(tool.isEnabled());
    }

    @Test
    void planTasksAddTaskFailure() throws Exception {
        when(autoModeService.addTask(anyString(), anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("Goal not found"));

        List<Map<String, Object>> tasksList = List.of(
                Map.of("title", "Failed task", "description", "Will fail"));

        Map<String, Object> params = new HashMap<>();
        params.put("operation", "plan_tasks");
        params.put("goal_id", "nonexistent");
        params.put("tasks", tasksList);

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Failed to add task"));
    }
}
