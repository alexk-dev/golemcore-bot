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

    private static final String OPERATION = "operation";
    private static final String GOAL_ID = "goal_id";
    private static final String GOAL_ID_G1 = "g1";
    private static final String TASK_ID = "task_id";
    private static final String TASK_ID_T1 = "t1";
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String STATUS = "status";
    private static final String CONTENT = "content";
    private static final String TASKS = "tasks";
    private static final String CREATE_GOAL = "create_goal";
    private static final String PLAN_TASKS = "plan_tasks";
    private static final String UPDATE_TASK_STATUS = "update_task_status";
    private static final String WRITE_DIARY = "write_diary";
    private static final String LEARN_JAVA = "Learn Java";
    private static final String STUDY_JAVA = "Study Java fundamentals";
    private static final String READ_CHAPTER_1 = "Read chapter 1";
    private static final String DO_EXERCISES = "Do exercises";

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
                .id(GOAL_ID_G1)
                .title(LEARN_JAVA)
                .description(STUDY_JAVA)
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(autoModeService.createGoal(LEARN_JAVA, STUDY_JAVA))
                .thenReturn(goal);

        ToolResult result = tool.execute(Map.of(
                OPERATION, CREATE_GOAL,
                TITLE, LEARN_JAVA,
                DESCRIPTION, STUDY_JAVA)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(LEARN_JAVA));
        assertTrue(result.getOutput().contains(GOAL_ID_G1));
        verify(autoModeService).createGoal(LEARN_JAVA, STUDY_JAVA);
    }

    @Test
    void createGoalMissingTitle() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, CREATE_GOAL)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(TITLE));
        verifyNoInteractions(autoModeService);
    }

    @Test
    void createGoalLimitReached() throws Exception {
        when(autoModeService.createGoal(anyString(), any()))
                .thenThrow(new IllegalStateException("Maximum active goals reached: 3"));

        ToolResult result = tool.execute(Map.of(
                OPERATION, CREATE_GOAL,
                TITLE, "Too many goals")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Maximum active goals reached"));
    }

    @Test
    void listGoalsWithGoals() throws Exception {
        AutoTask task1 = AutoTask.builder()
                .id(TASK_ID_T1).goalId(GOAL_ID_G1).title("Task 1")
                .status(AutoTask.TaskStatus.COMPLETED)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        AutoTask task2 = AutoTask.builder()
                .id("t2").goalId(GOAL_ID_G1).title("Task 2")
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        List<AutoTask> tasks = new ArrayList<>();
        tasks.add(task1);
        tasks.add(task2);

        Goal goal = Goal.builder()
                .id(GOAL_ID_G1).title(LEARN_JAVA)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(tasks)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.getGoals()).thenReturn(List.of(goal));

        ToolResult result = tool.execute(Map.of(
                OPERATION, "list_goals")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(LEARN_JAVA));
        assertTrue(result.getOutput().contains("1/2"));
        assertTrue(result.getOutput().contains("1"));
    }

    @Test
    void listGoalsEmpty() throws Exception {
        when(autoModeService.getGoals()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of(
                OPERATION, "list_goals")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No goals"));
    }

    @Test
    void planTasksSuccess() throws Exception {
        AutoTask task1 = AutoTask.builder()
                .id(TASK_ID_T1).goalId(GOAL_ID_G1).title(READ_CHAPTER_1)
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
        AutoTask task2 = AutoTask.builder()
                .id("t2").goalId(GOAL_ID_G1).title(DO_EXERCISES)
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.addTask(GOAL_ID_G1, READ_CHAPTER_1, "Read the first chapter", 1))
                .thenReturn(task1);
        when(autoModeService.addTask(GOAL_ID_G1, DO_EXERCISES, "Complete exercises", 2))
                .thenReturn(task2);

        List<Map<String, Object>> tasksList = List.of(
                Map.of(TITLE, READ_CHAPTER_1, DESCRIPTION, "Read the first chapter"),
                Map.of(TITLE, DO_EXERCISES, DESCRIPTION, "Complete exercises"));

        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(GOAL_ID, GOAL_ID_G1);
        params.put(TASKS, tasksList);

        ToolResult result = tool.execute(params).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(READ_CHAPTER_1));
        assertTrue(result.getOutput().contains(DO_EXERCISES));
        verify(autoModeService).addTask(GOAL_ID_G1, READ_CHAPTER_1, "Read the first chapter", 1);
        verify(autoModeService).addTask(GOAL_ID_G1, DO_EXERCISES, "Complete exercises", 2);
    }

    @Test
    void planTasksMissingGoalId() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(TASKS, List.of(Map.of(TITLE, "Task 1")));

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(GOAL_ID));
        verifyNoInteractions(autoModeService);
    }

    @Test
    void updateTaskStatusSuccess() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, UPDATE_TASK_STATUS,
                GOAL_ID, GOAL_ID_G1,
                TASK_ID, TASK_ID_T1,
                STATUS, "IN_PROGRESS")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("IN_PROGRESS"));
        verify(autoModeService).updateTaskStatus(GOAL_ID_G1, TASK_ID_T1,
                AutoTask.TaskStatus.IN_PROGRESS, null);
    }

    @Test
    void updateTaskStatusInvalidStatus() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, UPDATE_TASK_STATUS,
                GOAL_ID, GOAL_ID_G1,
                TASK_ID, TASK_ID_T1,
                STATUS, "BANANA")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid status"));
        assertTrue(result.getError().contains("BANANA"));
        verifyNoInteractions(autoModeService);
    }

    @Test
    void writeDiarySuccess() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_DIARY,
                CONTENT, "Made good progress today")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Diary entry written"));
        verify(autoModeService).writeDiary(any(DiaryEntry.class));
    }

    @Test
    void completeGoalSuccess() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, "complete_goal",
                GOAL_ID, GOAL_ID_G1)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("completed"));
        verify(autoModeService).completeGoal(GOAL_ID_G1);
    }

    @Test
    void disabledTool() throws Exception {
        BotProperties props = new BotProperties();
        props.getTools().getGoalManagement().setEnabled(false);
        GoalManagementTool disabledTool = new GoalManagementTool(props, autoModeService);

        ToolResult result = disabledTool.execute(Map.of(
                OPERATION, "list_goals")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("disabled"));
    }

    @Test
    void missingOperation() throws Exception {
        ToolResult result = tool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(OPERATION));
    }

    // ===== Unknown operation =====

    @Test
    void unknownOperation() throws Exception {
        ToolResult result = tool.execute(Map.of(OPERATION, "delete_everything")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    // ===== planTasks edge cases =====

    @Test
    void planTasksMissingTasks() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(GOAL_ID, GOAL_ID_G1);

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(TASKS));
    }

    @Test
    void planTasksEmptyList() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(GOAL_ID, GOAL_ID_G1);
        params.put(TASKS, List.of());

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("empty"));
    }

    @Test
    void planTasksWithMissingTitle() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(GOAL_ID, GOAL_ID_G1);
        params.put(TASKS, List.of(Map.of(DESCRIPTION, "No title")));

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("missing title"));
    }

    @Test
    void planTasksInvalidTasksType() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(GOAL_ID, GOAL_ID_G1);
        params.put(TASKS, "not a list");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("must be an array"));
    }

    // ===== updateTaskStatus edge cases =====

    @Test
    void updateTaskStatusMissingTaskId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, UPDATE_TASK_STATUS,
                GOAL_ID, GOAL_ID_G1,
                STATUS, "COMPLETED")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(TASK_ID));
    }

    @Test
    void updateTaskStatusMissingStatus() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, UPDATE_TASK_STATUS,
                GOAL_ID, GOAL_ID_G1,
                TASK_ID, TASK_ID_T1)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(STATUS));
    }

    @Test
    void updateTaskStatusMissingGoalId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, UPDATE_TASK_STATUS,
                TASK_ID, TASK_ID_T1,
                STATUS, "COMPLETED")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(GOAL_ID));
    }

    // ===== milestone callback =====

    @Test
    void updateTaskStatusCompletedTriggersCallback() throws Exception {
        java.util.concurrent.atomic.AtomicReference<GoalManagementTool.MilestoneEvent> event = new java.util.concurrent.atomic.AtomicReference<>();
        tool.setMilestoneCallback(event::set);

        AutoTask task = AutoTask.builder()
                .id(TASK_ID_T1).goalId(GOAL_ID_G1).title("Test task")
                .status(AutoTask.TaskStatus.COMPLETED)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        Goal goal = Goal.builder()
                .id(GOAL_ID_G1).title("Test Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.getGoal(GOAL_ID_G1)).thenReturn(java.util.Optional.of(goal));

        ToolResult result = tool.execute(Map.of(
                OPERATION, UPDATE_TASK_STATUS,
                GOAL_ID, GOAL_ID_G1,
                TASK_ID, TASK_ID_T1,
                STATUS, "COMPLETED")).get();

        assertTrue(result.isSuccess());
        assertNotNull(event.get());
        assertTrue(event.get().message().contains("Task completed"));
    }

    @Test
    void completeGoalTriggersCallback() throws Exception {
        java.util.concurrent.atomic.AtomicReference<GoalManagementTool.MilestoneEvent> event = new java.util.concurrent.atomic.AtomicReference<>();
        tool.setMilestoneCallback(event::set);

        Goal goal = Goal.builder()
                .id(GOAL_ID_G1).title("Completed Goal")
                .status(Goal.GoalStatus.COMPLETED)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        when(autoModeService.getGoal(GOAL_ID_G1)).thenReturn(java.util.Optional.of(goal));

        ToolResult result = tool.execute(Map.of(
                OPERATION, "complete_goal",
                GOAL_ID, GOAL_ID_G1)).get();

        assertTrue(result.isSuccess());
        assertNotNull(event.get());
        assertTrue(event.get().message().contains("Goal completed"));
    }

    // ===== writeDiary edge cases =====

    @Test
    void writeDiaryMissingContent() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_DIARY)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(CONTENT));
    }

    @Test
    void writeDiaryWithType() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_DIARY,
                CONTENT, "Progress update",
                "diary_type", "PROGRESS")).get();

        assertTrue(result.isSuccess());
        verify(autoModeService).writeDiary(argThat(entry -> entry.getType() == DiaryEntry.DiaryType.PROGRESS));
    }

    @Test
    void writeDiaryWithInvalidType() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_DIARY,
                CONTENT, "Some note",
                "diary_type", "INVALID_TYPE")).get();

        assertTrue(result.isSuccess()); // Should default to THOUGHT
        verify(autoModeService).writeDiary(argThat(entry -> entry.getType() == DiaryEntry.DiaryType.THOUGHT));
    }

    @Test
    void writeDiaryWithGoalAndTaskId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, WRITE_DIARY,
                CONTENT, "Working on task",
                GOAL_ID, GOAL_ID_G1,
                TASK_ID, TASK_ID_T1)).get();

        assertTrue(result.isSuccess());
        verify(autoModeService)
                .writeDiary(
                        argThat(entry -> GOAL_ID_G1.equals(entry.getGoalId()) && TASK_ID_T1.equals(entry.getTaskId())));
    }

    // ===== completeGoal edge cases =====

    @Test
    void completeGoalMissingGoalId() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, "complete_goal")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(GOAL_ID));
    }

    // ===== createGoal with blank title =====

    @Test
    void createGoalBlankTitle() throws Exception {
        ToolResult result = tool.execute(Map.of(
                OPERATION, CREATE_GOAL,
                TITLE, "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(TITLE));
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
                Map.of(TITLE, "Failed task", DESCRIPTION, "Will fail"));

        Map<String, Object> params = new HashMap<>();
        params.put(OPERATION, PLAN_TASKS);
        params.put(GOAL_ID, "nonexistent");
        params.put(TASKS, tasksList);

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Failed to add task"));
    }
}
