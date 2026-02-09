package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoModeServiceTest {

    private static final String AUTO_DIR = "auto";
    private static final String GOALS_FILE = "goals.json";
    private static final String GOAL_ID = "goal-1";
    private static final String TASK_ID = "task-1";
    private static final String DIARY_PREFIX = "diary/";

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private BotProperties properties;
    private AutoModeService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // for java.time Instant serialization

        properties = new BotProperties();
        properties.getAuto().setEnabled(true);
        properties.getAuto().setMaxGoals(3);
        properties.getAuto().setMaxTasksPerGoal(20);
        properties.getAuto().setMaxDiaryEntriesInContext(10);

        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.appendText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service = new AutoModeService(storagePort, objectMapper, properties);
    }

    @Test
    void createGoal_savesGoalViaStoragePort() throws Exception {
        Goal goal = service.createGoal("Learn Spring Boot", "Master Spring Boot framework");

        assertNotNull(goal);
        assertNotNull(goal.getId());
        assertEquals("Learn Spring Boot", goal.getTitle());
        assertEquals("Master Spring Boot framework", goal.getDescription());
        assertEquals(Goal.GoalStatus.ACTIVE, goal.getStatus());
        assertNotNull(goal.getCreatedAt());

        verify(storagePort).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
    }

    @Test
    void createGoal_throwsWhenMaxActiveGoalsReached() throws Exception {
        // Pre-load 3 active goals
        List<Goal> existingGoals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            existingGoals.add(Goal.builder()
                    .id("goal-" + i)
                    .title("Goal " + i)
                    .status(Goal.GoalStatus.ACTIVE)
                    .tasks(new ArrayList<>())
                    .createdAt(Instant.now())
                    .build());
        }
        String goalsJson = objectMapper.writeValueAsString(existingGoals);
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        assertThrows(IllegalStateException.class,
                () -> service.createGoal("One too many", "Should fail"));
    }

    @Test
    void getActiveGoals_returnsOnlyActiveGoals() throws Exception {
        List<Goal> goals = List.of(
                Goal.builder().id("g1").title("Active 1")
                        .status(Goal.GoalStatus.ACTIVE).tasks(new ArrayList<>()).createdAt(Instant.now()).build(),
                Goal.builder().id("g2").title("Completed")
                        .status(Goal.GoalStatus.COMPLETED).tasks(new ArrayList<>()).createdAt(Instant.now()).build(),
                Goal.builder().id("g3").title("Active 2")
                        .status(Goal.GoalStatus.ACTIVE).tasks(new ArrayList<>()).createdAt(Instant.now()).build());
        String goalsJson = objectMapper.writeValueAsString(goals);
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        List<Goal> activeGoals = service.getActiveGoals();

        assertEquals(2, activeGoals.size());
        assertTrue(activeGoals.stream().allMatch(g -> g.getStatus() == Goal.GoalStatus.ACTIVE));
    }

    @Test
    void addTask_addsTaskToGoal() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("Test Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        AutoTask task = service.addTask(GOAL_ID, "Write tests", "Write unit tests for service", 1);

        assertNotNull(task);
        assertNotNull(task.getId());
        assertEquals(GOAL_ID, task.getGoalId());
        assertEquals("Write tests", task.getTitle());
        assertEquals(AutoTask.TaskStatus.PENDING, task.getStatus());

        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
    }

    @Test
    void addTask_throwsIfGoalNotFound() throws Exception {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.addTask("nonexistent-goal", "Task", "Description", 1));
    }

    @Test
    void addTask_throwsWhenMaxTasksPerGoalReached() throws Exception {
        List<AutoTask> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(AutoTask.builder()
                    .id("task-" + i)
                    .goalId(GOAL_ID)
                    .title("Task " + i)
                    .status(AutoTask.TaskStatus.PENDING)
                    .order(i)
                    .createdAt(Instant.now())
                    .build());
        }
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("Full Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(tasks)
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        assertThrows(IllegalStateException.class,
                () -> service.addTask(GOAL_ID, "Too many", "Should fail", 21));
    }

    @Test
    void updateTaskStatus_updatesStatusAndWritesDiaryOnCompleted() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Do something")
                .status(AutoTask.TaskStatus.IN_PROGRESS)
                .order(0)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("Test Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.updateTaskStatus(GOAL_ID, TASK_ID, AutoTask.TaskStatus.COMPLETED, "Done successfully");

        // Verify goals saved
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());

        // Verify diary entry written for COMPLETED status
        verify(storagePort).appendText(eq(AUTO_DIR), contains(DIARY_PREFIX), anyString());
    }

    @Test
    void updateTaskStatus_throwsIfGoalOrTaskNotFound() throws Exception {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateTaskStatus("no-goal", "no-task",
                        AutoTask.TaskStatus.COMPLETED, "result"));
    }

    @Test
    void getNextPendingTask_returnsFirstPendingTaskAcrossActiveGoals() throws Exception {
        AutoTask pendingTask = AutoTask.builder()
                .id("task-2")
                .goalId(GOAL_ID)
                .title("Pending task")
                .status(AutoTask.TaskStatus.PENDING)
                .order(0)
                .createdAt(Instant.now())
                .build();
        AutoTask completedTask = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Completed task")
                .status(AutoTask.TaskStatus.COMPLETED)
                .order(0)
                .createdAt(Instant.now())
                .build();
        Goal goal1 = Goal.builder()
                .id(GOAL_ID)
                .title("First Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(completedTask, pendingTask)))
                .createdAt(Instant.now().minusSeconds(3600))
                .build();
        Goal goal2 = Goal.builder()
                .id("goal-2")
                .title("Completed Goal")
                .status(Goal.GoalStatus.COMPLETED)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal1, goal2));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        var next = service.getNextPendingTask();

        assertTrue(next.isPresent());
        assertEquals("task-2", next.get().getId());
        assertEquals(AutoTask.TaskStatus.PENDING, next.get().getStatus());
    }

    @Test
    void completeGoal_marksGoalAsCompletedAndWritesDiary() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("Completed Goal")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.completeGoal(GOAL_ID);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), captor.capture());

        String savedJson = captor.getValue();
        List<Goal> savedGoals = objectMapper.readValue(savedJson, new TypeReference<>() {
        });
        assertEquals(Goal.GoalStatus.COMPLETED, savedGoals.get(0).getStatus());

        // Verify diary entry for goal completion
        verify(storagePort).appendText(eq(AUTO_DIR), contains(DIARY_PREFIX), anyString());
    }

    @Test
    void enableAndDisableAutoMode() {
        assertFalse(service.isAutoModeEnabled());

        service.enableAutoMode();
        assertTrue(service.isAutoModeEnabled());

        service.disableAutoMode();
        assertFalse(service.isAutoModeEnabled());
    }

    @Test
    void buildAutoContext_returnsNullWhenNoActiveGoals() throws Exception {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        String context = service.buildAutoContext();

        assertNull(context);
    }

    @Test
    void buildAutoContext_formatsGoalsTasksDiaryIntoMarkdown() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Write code")
                .description("Implement the feature")
                .status(AutoTask.TaskStatus.PENDING)
                .order(0)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("Build feature X")
                .description("Implement feature X end-to-end")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        DiaryEntry entry = DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.PROGRESS)
                .content("Started working on feature X")
                .goalId(GOAL_ID)
                .build();
        String diaryLine = objectMapper.writeValueAsString(entry);
        // Diary is stored in date-based files: diary/{date}.jsonl
        String todayDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        when(storagePort.getText(AUTO_DIR, DIARY_PREFIX + todayDate + ".jsonl"))
                .thenReturn(CompletableFuture.completedFuture(diaryLine + "\n"));

        String context = service.buildAutoContext();

        assertNotNull(context);
        assertTrue(context.contains("Build feature X"));
        assertTrue(context.contains("Write code"));
        assertTrue(context.contains("Started working on feature X"));
    }

    @Test
    void isFeatureEnabled_returnsPropertiesSetting() {
        properties.getAuto().setEnabled(true);
        assertTrue(service.isFeatureEnabled());

        properties.getAuto().setEnabled(false);
        assertFalse(service.isFeatureEnabled());
    }

    @Test
    void writeDiary_callsStoragePortAppendText() throws Exception {
        DiaryEntry entry = DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.THOUGHT)
                .content("Thinking about the approach")
                .goalId(GOAL_ID)
                .build();

        service.writeDiary(entry);

        verify(storagePort).appendText(eq(AUTO_DIR), contains(DIARY_PREFIX), anyString());
    }
}
