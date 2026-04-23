package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoModeServiceTest {

    private static final String AUTO_DIR = "auto";
    private static final String GOALS_FILE = "goals.json";
    private static final String GOAL_ID = "goal-1";
    private static final String TASK_ID = "task-1";
    private static final String DIARY_PREFIX = "diary/";
    private static final String TEST_GOAL_TITLE = "Test Goal";

    private StoragePort storagePort;
    private ObjectMapper objectMapper;
    private RuntimeConfigService runtimeConfigService;
    private AutoModeService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(true);
        when(runtimeConfigService.getAutoMaxGoals()).thenReturn(3);
        when(runtimeConfigService.getAutoReflectionFailureThreshold()).thenReturn(2);
        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn("deep");
        when(runtimeConfigService.isAutoReflectionTierPriority()).thenReturn(false);

        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.appendText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service = new AutoModeService(storagePort, objectMapper, runtimeConfigService);
    }

    @Test
    void createGoalSavesGoalViaStoragePort() {
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
    void createGoalWithPromptPersistsPrompt() {
        Goal goal = service.createGoal("Release v2", "Prepare release train", "Ship version 2 with checklist");

        assertEquals("Ship version 2 with checklist", goal.getPrompt());
        verify(storagePort).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
    }

    @Test
    void createGoalWithReflectionTierPersistsReflectionSettings() {
        Goal goal = service.createGoal("Release v3", "Prepare release", "Prompt", "deep", true);

        assertEquals("deep", goal.getReflectionModelTier());
        assertTrue(goal.isReflectionTierPriority());
    }

    @Test
    void createGoalShouldIgnoreInboxWhenCountingActiveGoalLimit() throws Exception {
        when(runtimeConfigService.getAutoMaxGoals()).thenReturn(1);

        Goal inbox = Goal.builder()
                .id("inbox")
                .title("Inbox")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(inbox));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        Goal created = service.createGoal("Release v3", "Ship the release");

        assertEquals("Release v3", created.getTitle());
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
    }

    @Test
    void createGoalThrowsWhenMaxActiveGoalsReached() throws Exception {
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
    void getActiveGoalsReturnsOnlyActiveGoals() throws Exception {
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
    void addTaskAddsTaskToGoal() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
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
    void addTaskThrowsIfGoalNotFound() {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.addTask("nonexistent-goal", "Task", "Description", 1));
    }

    @Test
    void addTaskThrowsWhenMaxTasksPerGoalReached() throws Exception {
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
    void createTaskWithBlankGoalIdCreatesStandaloneInboxTaskAndNormalizesPrompt() {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        AutoTask task = service.createTask("   ", "Review alerts", "Investigate spikes", "   ", null, null, null);

        assertEquals("inbox", task.getGoalId());
        assertEquals("Review alerts", task.getTitle());
        assertNull(task.getPrompt());
        assertEquals(AutoTask.TaskStatus.PENDING, task.getStatus());
        assertEquals(1, service.getOrCreateInboxGoal().getTasks().size());
    }

    @Test
    void createTaskWithReflectionTierPersistsTaskReflectionSettings() {
        AutoTask task = service.createTask(null, "Review alerts", "Investigate spikes", "Prompt", "smart", true,
                AutoTask.TaskStatus.PENDING);

        assertEquals("smart", task.getReflectionModelTier());
        assertTrue(task.isReflectionTierPriority());
    }

    @Test
    void updateGoalUpdatesFieldsAndStatus() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("Old title")
                .description("Old description")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        Goal updated = service.updateGoal(
                GOAL_ID,
                "New title",
                "New description",
                "Execute updated prompt",
                "deep",
                true,
                Goal.GoalStatus.PAUSED);

        assertEquals("New title", updated.getTitle());
        assertEquals("New description", updated.getDescription());
        assertEquals("Execute updated prompt", updated.getPrompt());
        assertEquals("deep", updated.getReflectionModelTier());
        assertTrue(updated.isReflectionTierPriority());
        assertEquals(Goal.GoalStatus.PAUSED, updated.getStatus());
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
    }

    @Test
    void updateGoalThrowsWhenEditingInboxGoal() throws Exception {
        Goal inbox = Goal.builder()
                .id("inbox")
                .title("Inbox")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(inbox));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateGoal("inbox", "Inbox", "Desc", "Prompt", null, null, Goal.GoalStatus.ACTIVE));

        assertEquals("Inbox goal cannot be edited", exception.getMessage());
    }

    @Test
    void updateTaskStatusUpdatesStatusAndWritesDiaryOnCompleted() throws Exception {
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
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.updateTaskStatus(GOAL_ID, TASK_ID, AutoTask.TaskStatus.COMPLETED, "Done successfully");

        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
        verify(storagePort).appendText(eq(AUTO_DIR), contains(DIARY_PREFIX), anyString());
    }

    @Test
    void updateTaskStatusFailedIncrementsFailureCounterAndSetsReflectionRequiredAtThreshold() throws Exception {
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
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.updateTaskStatus(GOAL_ID, TASK_ID, AutoTask.TaskStatus.FAILED, "First failure");
        service.updateTaskStatus(GOAL_ID, TASK_ID, AutoTask.TaskStatus.FAILED, "Second failure");

        AutoTask updated = service.getTask(TASK_ID).orElseThrow();
        assertEquals(2, updated.getConsecutiveFailureCount());
        assertTrue(updated.isReflectionRequired());
        assertEquals("Second failure", updated.getLastFailureSummary());
    }

    @Test
    void updateTaskStatusThrowsIfGoalOrTaskNotFound() {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateTaskStatus("no-goal", "no-task",
                        AutoTask.TaskStatus.COMPLETED, "result"));
    }

    @Test
    void updateTaskUpdatesPromptAndStatusAndWritesDiaryWhenCompleted() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Old task")
                .description("Old description")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        AutoTask updated = service.updateTask(
                TASK_ID,
                "New task",
                "New description",
                "Run the deeper task prompt",
                "deep",
                true,
                AutoTask.TaskStatus.COMPLETED);

        assertEquals("New task", updated.getTitle());
        assertEquals("New description", updated.getDescription());
        assertEquals("Run the deeper task prompt", updated.getPrompt());
        assertEquals("deep", updated.getReflectionModelTier());
        assertTrue(updated.isReflectionTierPriority());
        assertEquals(AutoTask.TaskStatus.COMPLETED, updated.getStatus());
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
        verify(storagePort).appendText(eq(AUTO_DIR), contains(DIARY_PREFIX), anyString());
    }

    @Test
    void getNextPendingTaskReturnsFirstPendingTaskAcrossActiveGoals() throws Exception {
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

        Optional<AutoTask> next = service.getNextPendingTask();

        assertTrue(next.isPresent());
        assertEquals("task-2", next.get().getId());
        assertEquals(AutoTask.TaskStatus.PENDING, next.get().getStatus());
    }

    @Test
    void completeGoalMarksGoalAsCompletedAndWritesDiary() throws Exception {
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
    void buildAutoContextReturnsNullWhenNoActiveGoals() {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        String context = service.buildAutoContext();

        assertNull(context);
    }

    @Test
    void buildAutoContextFormatsGoalsTasksDiaryIntoMarkdown() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Write code")
                .description("Implement the feature")
                .status(AutoTask.TaskStatus.PENDING)
                .order(0)
                .reflectionStrategy("Try a different MCP tool and validate access first")
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
        Goal otherGoal = Goal.builder()
                .id("goal-2")
                .title("Unrelated maintenance")
                .description("Noisy description that should not be injected")
                .prompt("Noisy prompt that should not be injected")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal, otherGoal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        DiaryEntry entry = DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.PROGRESS)
                .content("Started working on feature X")
                .goalId(GOAL_ID)
                .taskId(TASK_ID)
                .build();
        DiaryEntry unrelatedEntry = DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.PROGRESS)
                .content("Unrelated diary noise")
                .goalId("goal-2")
                .build();
        String diaryLine = objectMapper.writeValueAsString(unrelatedEntry) + "\n"
                + objectMapper.writeValueAsString(entry);
        String todayDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        when(storagePort.getText(AUTO_DIR, DIARY_PREFIX + todayDate + ".jsonl"))
                .thenReturn(CompletableFuture.completedFuture(diaryLine + "\n"));

        String context = service.buildAutoContext(GOAL_ID, TASK_ID);

        assertNotNull(context);
        assertTrue(context.contains("Build feature X"));
        assertTrue(context.contains("Write code"));
        assertTrue(context.contains("Started working on feature X"));
        assertTrue(context.contains("Recovery strategy"));
        assertTrue(context.contains("Other Active Goals (summary)"));
        assertTrue(context.contains("Unrelated maintenance"));
        assertFalse(context.contains("Noisy description that should not be injected"));
        assertFalse(context.contains("Noisy prompt that should not be injected"));
        assertFalse(context.contains("Unrelated diary noise"));
    }

    @Test
    void recordAutoRunFailureAndReflectionResultShouldUpdateState() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Write code")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .reflectionModelTier("deep")
                .reflectionTierPriority(true)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.recordAutoRunFailure(GOAL_ID, TASK_ID, "tool timeout", "tool timeout", "reviewer-skill");
        service.recordAutoRunFailure(GOAL_ID, TASK_ID, "tool timeout", "tool timeout", "reviewer-skill");

        assertTrue(service.shouldTriggerReflection(GOAL_ID, TASK_ID));
        assertEquals("deep", service.resolveReflectionTier(GOAL_ID, TASK_ID));
        assertTrue(service.isReflectionTierPriority(GOAL_ID, TASK_ID));

        service.applyReflectionResult(GOAL_ID, TASK_ID, "Use a different tool and verify permissions first");

        AutoTask updated = service.getTask(TASK_ID).orElseThrow();
        assertEquals(0, updated.getConsecutiveFailureCount());
        assertFalse(updated.isReflectionRequired());
        assertEquals("Use a different tool and verify permissions first", updated.getReflectionStrategy());
        assertEquals("reviewer-skill", updated.getLastUsedSkillName());
        assertEquals(AutoTask.TaskStatus.IN_PROGRESS, updated.getStatus());
    }

    @Test
    void shouldTrackGoalLevelFailureAndReflectionStateWhenTaskIdIsMissing() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .reflectionModelTier("deep")
                .reflectionTierPriority(true)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.recordAutoRunFailure(GOAL_ID, null, "planner timeout", "planner timeout", "planner-skill");
        service.recordAutoRunFailure(GOAL_ID, null, "planner timeout", "planner timeout", "planner-skill");

        Goal updatedGoal = service.getGoal(GOAL_ID).orElseThrow();
        assertEquals(2, updatedGoal.getConsecutiveFailureCount());
        assertTrue(updatedGoal.isReflectionRequired());
        assertEquals("planner-skill", updatedGoal.getLastUsedSkillName());
        assertTrue(service.shouldTriggerReflection(GOAL_ID, null));
        assertEquals("deep", service.resolveReflectionTier(GOAL_ID, null));
        assertTrue(service.isReflectionTierPriority(GOAL_ID, null));

        service.applyReflectionResult(GOAL_ID, null, "Try a narrower planning prompt and summarize blockers first");

        Goal reflectedGoal = service.getGoal(GOAL_ID).orElseThrow();
        assertEquals(0, reflectedGoal.getConsecutiveFailureCount());
        assertFalse(reflectedGoal.isReflectionRequired());
        assertEquals("Try a narrower planning prompt and summarize blockers first",
                reflectedGoal.getReflectionStrategy());
        assertEquals("planner-skill", reflectedGoal.getLastUsedSkillName());
    }

    @Test
    void resolveReflectionTierShouldPreferUsedSkillWhenTaskTierIsNotPriority() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Write code")
                .reflectionModelTier("smart")
                .reflectionTierPriority(false)
                .lastUsedSkillName("reviewer-skill")
                .status(AutoTask.TaskStatus.FAILED)
                .order(1)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        Skill skill = Skill.builder()
                .name("reviewer-skill")
                .reflectionTier("deep")
                .build();

        assertEquals("deep", service.resolveReflectionTier(GOAL_ID, TASK_ID, skill));
    }

    @Test
    void isFeatureEnabledReturnsPropertiesSetting() {
        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(true);
        assertTrue(service.isFeatureEnabled());

        when(runtimeConfigService.isAutoModeEnabled()).thenReturn(false);
        assertFalse(service.isFeatureEnabled());
    }

    @Test
    void deleteGoalRemovesGoalAndSaves() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title("To Delete")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.deleteGoal(GOAL_ID);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), captor.capture());
        List<Goal> savedGoals = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        assertTrue(savedGoals.isEmpty());
        verify(storagePort).appendText(eq(AUTO_DIR), contains(DIARY_PREFIX), anyString());
    }

    @Test
    void deleteGoalThrowsIfNotFound() {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteGoal("nonexistent"));
    }

    @Test
    void deleteTaskRemovesTaskFromGoal() throws Exception {
        AutoTask task = AutoTask.builder()
                .id(TASK_ID)
                .goalId(GOAL_ID)
                .title("Task to remove")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(task)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.deleteTask(GOAL_ID, TASK_ID);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), captor.capture());
        List<Goal> savedGoals = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        assertTrue(savedGoals.get(0).getTasks().isEmpty());
    }

    @Test
    void deleteTaskByTaskIdRebalancesRemainingOrders() throws Exception {
        AutoTask firstTask = AutoTask.builder()
                .id("task-1")
                .goalId(GOAL_ID)
                .title("First")
                .status(AutoTask.TaskStatus.PENDING)
                .order(1)
                .createdAt(Instant.now().minusSeconds(60))
                .build();
        AutoTask secondTask = AutoTask.builder()
                .id("task-2")
                .goalId(GOAL_ID)
                .title("Second")
                .status(AutoTask.TaskStatus.PENDING)
                .order(2)
                .createdAt(Instant.now().minusSeconds(30))
                .build();
        AutoTask thirdTask = AutoTask.builder()
                .id("task-3")
                .goalId(GOAL_ID)
                .title("Third")
                .status(AutoTask.TaskStatus.PENDING)
                .order(3)
                .createdAt(Instant.now())
                .build();
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>(List.of(firstTask, secondTask, thirdTask)))
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        service.deleteTask("task-2");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), captor.capture());
        List<Goal> savedGoals = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        List<AutoTask> savedTasks = savedGoals.get(0).getTasks();
        assertEquals(2, savedTasks.size());
        assertEquals("task-1", savedTasks.get(0).getId());
        assertEquals(1, savedTasks.get(0).getOrder());
        assertEquals("task-3", savedTasks.get(1).getId());
        assertEquals(2, savedTasks.get(1).getOrder());
    }

    @Test
    void deleteTaskThrowsIfGoalNotFound() {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteTask("no-goal", "no-task"));
    }

    @Test
    void deleteTaskThrowsIfTaskNotFound() throws Exception {
        Goal goal = Goal.builder()
                .id(GOAL_ID)
                .title(TEST_GOAL_TITLE)
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteTask(GOAL_ID, "nonexistent-task"));
    }

    @Test
    void clearCompletedGoalsRemovesCompletedAndCancelledGoals() throws Exception {
        List<Goal> goals = new ArrayList<>(List.of(
                Goal.builder().id("g1").title("Active")
                        .status(Goal.GoalStatus.ACTIVE).tasks(new ArrayList<>()).createdAt(Instant.now()).build(),
                Goal.builder().id("g2").title("Completed")
                        .status(Goal.GoalStatus.COMPLETED).tasks(new ArrayList<>()).createdAt(Instant.now()).build(),
                Goal.builder().id("g3").title("Cancelled")
                        .status(Goal.GoalStatus.CANCELLED).tasks(new ArrayList<>()).createdAt(Instant.now()).build(),
                Goal.builder().id("g4").title("Paused")
                        .status(Goal.GoalStatus.PAUSED).tasks(new ArrayList<>()).createdAt(Instant.now()).build()));
        String goalsJson = objectMapper.writeValueAsString(goals);
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        int removed = service.clearCompletedGoals();

        assertEquals(2, removed);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), captor.capture());
        List<Goal> savedGoals = objectMapper.readValue(captor.getValue(), new TypeReference<>() {
        });
        assertEquals(2, savedGoals.size());
        assertTrue(savedGoals.stream().noneMatch(g -> g.getStatus() == Goal.GoalStatus.COMPLETED));
        assertTrue(savedGoals.stream().noneMatch(g -> g.getStatus() == Goal.GoalStatus.CANCELLED));
    }

    @Test
    void clearCompletedGoalsReturnsZeroWhenNothingToRemove() throws Exception {
        Goal goal = Goal.builder()
                .id("g1")
                .title("Active")
                .status(Goal.GoalStatus.ACTIVE)
                .tasks(new ArrayList<>())
                .createdAt(Instant.now())
                .build();
        String goalsJson = objectMapper.writeValueAsString(List.of(goal));
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(goalsJson));

        int removed = service.clearCompletedGoals();

        assertEquals(0, removed);
    }

    @Test
    void shouldSupportInboxGoalAndStandaloneTaskFlow() throws Exception {
        when(storagePort.getText(AUTO_DIR, GOALS_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        Goal inbox = service.getOrCreateInboxGoal();
        assertNotNull(inbox);
        assertEquals("inbox", inbox.getId());
        assertTrue(service.isInboxGoal(inbox));
        assertEquals("inbox", service.getInboxGoalId());

        AutoTask task = service.addStandaloneTask("Inbox task", "From menu");
        assertNotNull(task);
        assertEquals("inbox", task.getGoalId());
        assertEquals("Inbox task", task.getTitle());

        Goal resolvedInbox = service.getOrCreateInboxGoal();
        assertEquals("inbox", resolvedInbox.getId());
        assertFalse(service.isInboxGoal(null));
        assertFalse(service.isInboxGoal(Goal.builder().id("g-other").build()));

        verify(storagePort, atLeastOnce()).putText(eq(AUTO_DIR), eq(GOALS_FILE), anyString());
    }

    @Test
    void writeDiaryCallsStoragePortAppendText() {
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
