package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core service for autonomous agent mode managing goals, tasks, diary entries,
 * and global state. Single-user design with simplified storage layout. Auto
 * mode enables the agent to work independently on long-term goals, breaking
 * them into tasks and maintaining a daily diary of progress. Integrates with
 * {@link auto.AutoModeScheduler} for periodic agent invocations routed through
 * the shared per-session run coordinator.
 * <p>
 * Storage layout:
 * <ul>
 * <li>auto/state.json - global enabled state</li>
 * <li>auto/goals.json - list of all goals</li>
 * <li>auto/diary/{date}.jsonl - diary entries per day</li>
 * </ul>
 */
@Service
@Slf4j
public class AutoModeService {

    private static final String AUTO_DIR = "auto";
    private static final String GOAL_NOT_FOUND = "Goal not found: ";
    private static final String TASK_NOT_FOUND = "Task not found: ";
    private static final String INBOX_GOAL_ID = "inbox";
    private static final String INBOX_GOAL_TITLE = "Inbox";
    private static final String INBOX_GOAL_DESCRIPTION = "System container for standalone tasks";
    private static final int MAX_TASKS_PER_GOAL = 20;
    private static final TypeReference<List<Goal>> GOAL_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final RuntimeConfigService runtimeConfigService;

    // In-memory state
    private volatile boolean enabled = false;
    private volatile List<Goal> goalsCache;

    public AutoModeService(StoragePort storagePort, ObjectMapper objectMapper,
            RuntimeConfigService runtimeConfigService) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
        this.runtimeConfigService = runtimeConfigService;
    }

    public boolean isFeatureEnabled() {
        return runtimeConfigService.isAutoModeEnabled();
    }

    // ==================== State management ====================

    public boolean isAutoModeEnabled() {
        return enabled;
    }

    public void enableAutoMode() {
        enabled = true;
        saveState(true);
        log.info("[AutoMode] Enabled");
    }

    public void disableAutoMode() {
        enabled = false;
        saveState(false);
        log.info("[AutoMode] Disabled");
    }

    // ==================== Goal management ====================

    public Goal createGoal(String title, String description) {
        return createGoal(title, description, null, null, false);
    }

    public Goal createGoal(String title, String description, String prompt) {
        return createGoal(title, description, prompt, null, false);
    }

    public Goal createGoal(String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        List<Goal> goals = getGoals();

        long activeCount = goals.stream()
                .filter(g -> !isInboxGoal(g))
                .filter(g -> g.getStatus() == Goal.GoalStatus.ACTIVE)
                .count();
        if (activeCount >= runtimeConfigService.getAutoMaxGoals()) {
            throw new IllegalStateException("Maximum active goals reached: " + runtimeConfigService.getAutoMaxGoals());
        }

        Instant now = Instant.now();
        Goal goal = Goal.builder()
                .id(UUID.randomUUID().toString())
                .title(requireTitle(title, "Goal title is required"))
                .description(normalizeOptionalValue(description))
                .prompt(normalizeOptionalValue(prompt))
                .reflectionModelTier(normalizeOptionalValue(reflectionModelTier))
                .reflectionTierPriority(reflectionTierPriority)
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        goals.add(goal);
        saveGoals(goals);
        log.info("[AutoMode] Created goal '{}'", goal.getTitle());
        return goal;
    }

    public synchronized List<Goal> getGoals() {
        if (goalsCache == null) {
            goalsCache = loadGoals();
        }
        return goalsCache;
    }

    public List<Goal> getActiveGoals() {
        return getGoals().stream()
                .filter(g -> g.getStatus() == Goal.GoalStatus.ACTIVE)
                .toList();
    }

    public Optional<Goal> getGoal(String goalId) {
        return getGoals().stream()
                .filter(g -> g.getId().equals(goalId))
                .findFirst();
    }

    public Goal updateGoal(String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, Goal.GoalStatus status) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        if (isInboxGoal(goal)) {
            throw new IllegalArgumentException("Inbox goal cannot be edited");
        }

        Goal.GoalStatus resolvedStatus = status != null ? status : goal.getStatus();
        long activeCount = getGoals().stream()
                .filter(item -> !item.getId().equals(goalId))
                .filter(item -> !isInboxGoal(item))
                .filter(item -> item.getStatus() == Goal.GoalStatus.ACTIVE)
                .count();
        if (resolvedStatus == Goal.GoalStatus.ACTIVE && activeCount >= runtimeConfigService.getAutoMaxGoals()) {
            throw new IllegalStateException("Maximum active goals reached: " + runtimeConfigService.getAutoMaxGoals());
        }

        Instant now = Instant.now();
        goal.setTitle(requireTitle(title, "Goal title is required"));
        goal.setDescription(normalizeOptionalValue(description));
        goal.setPrompt(normalizeOptionalValue(prompt));
        goal.setReflectionModelTier(normalizeOptionalValue(reflectionModelTier));
        if (reflectionTierPriority != null) {
            goal.setReflectionTierPriority(reflectionTierPriority);
        }
        goal.setStatus(resolvedStatus);
        goal.setUpdatedAt(now);
        saveGoals(getGoals());

        log.info("[AutoMode] Updated goal '{}'", goal.getTitle());
        return goal;
    }

    public AutoTask addTask(String goalId, String title, String description, int order) {
        return addTask(goalId, title, description, null, null, false, order);
    }

    public AutoTask addTask(String goalId, String title, String description, String prompt, int order) {
        return addTask(goalId, title, description, prompt, null, false, order);
    }

    public AutoTask addTask(String goalId, String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority, int order) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        validateTaskCapacity(goal);

        Instant now = Instant.now();
        AutoTask task = AutoTask.builder()
                .id(UUID.randomUUID().toString())
                .goalId(goalId)
                .title(requireTitle(title, "Task title is required"))
                .description(normalizeOptionalValue(description))
                .prompt(normalizeOptionalValue(prompt))
                .reflectionModelTier(normalizeOptionalValue(reflectionModelTier))
                .reflectionTierPriority(reflectionTierPriority)
                .order(order > 0 ? order : nextTaskOrder(goal))
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        goal.getTasks().add(task);
        goal.setUpdatedAt(now);
        rebalanceTaskOrders(goal);
        saveGoals(getGoals());
        log.info("[AutoMode] Added task '{}' to goal '{}'", task.getTitle(), goal.getTitle());
        return task;
    }

    public AutoTask createTask(String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, AutoTask.TaskStatus status) {
        Goal targetGoal = resolveTaskGoal(goalId);
        validateTaskCapacity(targetGoal);

        Instant now = Instant.now();
        AutoTask task = AutoTask.builder()
                .id(UUID.randomUUID().toString())
                .goalId(targetGoal.getId())
                .title(requireTitle(title, "Task title is required"))
                .description(normalizeOptionalValue(description))
                .prompt(normalizeOptionalValue(prompt))
                .reflectionModelTier(normalizeOptionalValue(reflectionModelTier))
                .reflectionTierPriority(Boolean.TRUE.equals(reflectionTierPriority))
                .status(status != null ? status : AutoTask.TaskStatus.PENDING)
                .order(nextTaskOrder(targetGoal))
                .createdAt(now)
                .updatedAt(now)
                .build();

        targetGoal.getTasks().add(task);
        targetGoal.setUpdatedAt(now);
        saveGoals(getGoals());
        log.info("[AutoMode] Created task '{}' in goal '{}'", task.getTitle(), targetGoal.getTitle());
        return task;
    }

    /**
     * Add a standalone task to the system Inbox goal.
     */
    public AutoTask addStandaloneTask(String title, String description) {
        return addStandaloneTask(title, description, null, null, false);
    }

    public AutoTask addStandaloneTask(String title, String description, String prompt) {
        return addStandaloneTask(title, description, prompt, null, false);
    }

    public AutoTask addStandaloneTask(String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        return createTask(null, title, description, prompt, reflectionModelTier, reflectionTierPriority,
                AutoTask.TaskStatus.PENDING);
    }

    public boolean isInboxGoal(Goal goal) {
        if (goal == null || goal.getId() == null) {
            return false;
        }
        return INBOX_GOAL_ID.equals(goal.getId());
    }

    public String getInboxGoalId() {
        return INBOX_GOAL_ID;
    }

    public Goal getOrCreateInboxGoal() {
        Optional<Goal> existing = getGoal(INBOX_GOAL_ID);
        if (existing.isPresent()) {
            return existing.get();
        }

        List<Goal> goals = getGoals();
        Instant now = Instant.now();
        Goal inboxGoal = Goal.builder()
                .id(INBOX_GOAL_ID)
                .title(INBOX_GOAL_TITLE)
                .description(INBOX_GOAL_DESCRIPTION)
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        goals.add(inboxGoal);
        saveGoals(goals);
        log.info("[AutoMode] Created system inbox goal");
        return inboxGoal;
    }

    public void updateTaskStatus(String goalId, String taskId,
            AutoTask.TaskStatus status, String result) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        AutoTask task = goal.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));

        Instant now = Instant.now();
        task.setStatus(status);
        task.setResult(result);
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);

        if (status == AutoTask.TaskStatus.COMPLETED) {
            resetTaskFailureState(task);
        } else if (status == AutoTask.TaskStatus.FAILED) {
            task.setConsecutiveFailureCount(task.getConsecutiveFailureCount() + 1);
            task.setLastFailureSummary(normalizeOptionalValue(result));
            task.setLastFailureFingerprint(buildFailureFingerprint(result));
            task.setLastFailureAt(now);
            task.setReflectionRequired(task.getConsecutiveFailureCount() >= getReflectionFailureThreshold());
        }

        saveGoals(getGoals());

        if (status == AutoTask.TaskStatus.COMPLETED) {
            writeDiary(DiaryEntry.builder()
                    .timestamp(now)
                    .type(DiaryEntry.DiaryType.PROGRESS)
                    .content("Completed task: " + task.getTitle() +
                            (result != null ? " — " + result : ""))
                    .goalId(goalId)
                    .taskId(taskId)
                    .build());
        }

        log.info("[AutoMode] Updated task '{}' status to {}", task.getTitle(), status);
    }

    public Optional<AutoTask> getTask(String taskId) {
        return findTaskLocation(taskId).map(TaskLocation::task);
    }

    public AutoTask updateTask(
            String taskId,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority,
            AutoTask.TaskStatus status) {
        TaskLocation location = findTaskLocation(taskId)
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));

        Goal goal = location.goal();
        AutoTask task = location.task();
        AutoTask.TaskStatus previousStatus = task.getStatus();
        AutoTask.TaskStatus resolvedStatus = status != null ? status : task.getStatus();
        Instant now = Instant.now();

        task.setTitle(requireTitle(title, "Task title is required"));
        task.setDescription(normalizeOptionalValue(description));
        task.setPrompt(normalizeOptionalValue(prompt));
        task.setReflectionModelTier(normalizeOptionalValue(reflectionModelTier));
        if (reflectionTierPriority != null) {
            task.setReflectionTierPriority(reflectionTierPriority);
        }
        task.setStatus(resolvedStatus);
        task.setUpdatedAt(now);
        if (resolvedStatus == AutoTask.TaskStatus.COMPLETED && previousStatus != AutoTask.TaskStatus.COMPLETED) {
            resetTaskFailureState(task);
        }
        goal.setUpdatedAt(now);
        saveGoals(getGoals());

        if (resolvedStatus == AutoTask.TaskStatus.COMPLETED && previousStatus != AutoTask.TaskStatus.COMPLETED) {
            writeDiary(DiaryEntry.builder()
                    .timestamp(now)
                    .type(DiaryEntry.DiaryType.PROGRESS)
                    .content("Completed task: " + task.getTitle())
                    .goalId(goal.getId())
                    .taskId(taskId)
                    .build());
        }

        log.info("[AutoMode] Updated task '{}'", task.getTitle());
        return task;
    }

    public Optional<AutoTask> getNextPendingTask() {
        return getActiveGoals().stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt))
                .flatMap(g -> g.getTasks().stream()
                        .filter(t -> t.getStatus() == AutoTask.TaskStatus.PENDING)
                        .sorted(Comparator.comparingInt(AutoTask::getOrder)))
                .findFirst();
    }

    public void completeGoal(String goalId) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        goal.setStatus(Goal.GoalStatus.COMPLETED);
        goal.setUpdatedAt(Instant.now());
        saveGoals(getGoals());

        writeDiary(DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.PROGRESS)
                .content("Goal completed: " + goal.getTitle())
                .goalId(goalId)
                .build());

        log.info("[AutoMode] Completed goal '{}'", goal.getTitle());
    }

    public void deleteGoal(String goalId) {
        if (INBOX_GOAL_ID.equals(goalId)) {
            throw new IllegalArgumentException("Inbox goal cannot be deleted");
        }

        List<Goal> goals = getGoals();
        boolean removed = goals.removeIf(g -> g.getId().equals(goalId));
        if (!removed) {
            throw new IllegalArgumentException(GOAL_NOT_FOUND + goalId);
        }
        saveGoals(goals);

        writeDiary(DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.DECISION)
                .content("Goal deleted: " + goalId)
                .goalId(goalId)
                .build());

        log.info("[AutoMode] Deleted goal '{}'", goalId);
    }

    public void deleteTask(String goalId, String taskId) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        boolean removed = goal.getTasks().removeIf(t -> t.getId().equals(taskId));
        if (!removed) {
            throw new IllegalArgumentException(TASK_NOT_FOUND + taskId);
        }

        goal.setUpdatedAt(Instant.now());
        rebalanceTaskOrders(goal);
        saveGoals(getGoals());
        log.info("[AutoMode] Deleted task '{}' from goal '{}'", taskId, goal.getTitle());
    }

    public void deleteTask(String taskId) {
        TaskLocation location = findTaskLocation(taskId)
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        deleteTask(location.goal().getId(), taskId);
    }

    public int clearCompletedGoals() {
        List<Goal> goals = getGoals();
        int before = goals.size();
        goals.removeIf(g -> g.getStatus() == Goal.GoalStatus.COMPLETED
                || g.getStatus() == Goal.GoalStatus.CANCELLED);
        int removed = before - goals.size();

        if (removed > 0) {
            saveGoals(goals);
            log.info("[AutoMode] Cleared {} completed/cancelled goals", removed);
        }

        return removed;
    }

    // ==================== Auto reflection ====================

    public void recordAutoRunFailure(String goalId, String taskId, String failureSummary, String failureFingerprint,
            String activeSkillName) {
        if (StringValueSupport.isBlank(goalId)) {
            return;
        }

        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        Instant now = Instant.now();
        String normalizedFailureSummary = normalizeOptionalValue(failureSummary);
        String normalizedFailureFingerprint = buildFailureFingerprint(
                failureFingerprint != null ? failureFingerprint : failureSummary);
        String normalizedActiveSkillName = normalizeOptionalValue(activeSkillName);

        if (StringValueSupport.isBlank(taskId)) {
            goal.setConsecutiveFailureCount(goal.getConsecutiveFailureCount() + 1);
            goal.setLastFailureSummary(normalizedFailureSummary);
            goal.setLastFailureFingerprint(normalizedFailureFingerprint);
            goal.setLastFailureAt(now);
            goal.setLastUsedSkillName(normalizedActiveSkillName);
            goal.setReflectionRequired(goal.getConsecutiveFailureCount() >= getReflectionFailureThreshold());
            goal.setUpdatedAt(now);
            saveGoals(getGoals());
            return;
        }

        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));

        task.setStatus(AutoTask.TaskStatus.FAILED);
        task.setResult(normalizedFailureSummary);
        task.setConsecutiveFailureCount(task.getConsecutiveFailureCount() + 1);
        task.setLastFailureSummary(normalizedFailureSummary);
        task.setLastFailureFingerprint(normalizedFailureFingerprint);
        task.setLastFailureAt(now);
        task.setLastUsedSkillName(normalizedActiveSkillName);
        task.setReflectionRequired(task.getConsecutiveFailureCount() >= getReflectionFailureThreshold());
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);
        saveGoals(getGoals());
    }

    public void recordAutoRunSuccess(String goalId, String taskId, String activeSkillName) {
        if (StringValueSupport.isBlank(goalId)) {
            return;
        }

        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        String normalizedActiveSkillName = normalizeOptionalValue(activeSkillName);
        Instant now = Instant.now();

        if (StringValueSupport.isBlank(taskId)) {
            resetGoalFailureState(goal);
            goal.setLastUsedSkillName(normalizedActiveSkillName);
            goal.setUpdatedAt(now);
            saveGoals(getGoals());
            return;
        }

        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));

        resetTaskFailureState(task);
        task.setLastUsedSkillName(normalizedActiveSkillName);
        if (task.getStatus() == AutoTask.TaskStatus.FAILED) {
            task.setStatus(AutoTask.TaskStatus.IN_PROGRESS);
        }
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);
        saveGoals(getGoals());
    }

    public boolean shouldTriggerReflection(String goalId, String taskId) {
        return resolveTaskReflectionState(goalId, taskId).reflectionRequired();
    }

    public void applyReflectionResult(String goalId, String taskId, String reflectionStrategy) {
        if (StringValueSupport.isBlank(goalId)) {
            return;
        }

        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        Instant now = Instant.now();
        String normalizedReflectionStrategy = normalizeOptionalValue(reflectionStrategy);
        if (StringValueSupport.isBlank(taskId)) {
            goal.setReflectionStrategy(normalizedReflectionStrategy);
            goal.setReflectionRequired(false);
            goal.setConsecutiveFailureCount(0);
            goal.setLastReflectionAt(now);
            goal.setUpdatedAt(now);
            saveGoals(getGoals());

            if (goal.getReflectionStrategy() != null) {
                writeDiary(DiaryEntry.builder()
                        .timestamp(now)
                        .type(DiaryEntry.DiaryType.OBSERVATION)
                        .content("Reflection strategy for goal '" + goal.getTitle() + "': "
                                + goal.getReflectionStrategy())
                        .goalId(goalId)
                        .build());
            }
            return;
        }

        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));

        task.setReflectionStrategy(normalizedReflectionStrategy);
        task.setReflectionRequired(false);
        task.setConsecutiveFailureCount(0);
        task.setLastReflectionAt(now);
        if (task.getStatus() == AutoTask.TaskStatus.FAILED) {
            task.setStatus(AutoTask.TaskStatus.IN_PROGRESS);
        }
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);
        saveGoals(getGoals());

        if (task.getReflectionStrategy() != null) {
            writeDiary(DiaryEntry.builder()
                    .timestamp(now)
                    .type(DiaryEntry.DiaryType.OBSERVATION)
                    .content("Reflection strategy for task '" + task.getTitle() + "': " + task.getReflectionStrategy())
                    .goalId(goalId)
                    .taskId(taskId)
                    .build());
        }
    }

    public TaskReflectionState resolveTaskReflectionState(String goalId, String taskId) {
        return getTaskReflectionState(goalId, taskId)
                .orElse(new TaskReflectionState(null, false, null, false, null, 0, false, null, null, null));
    }

    public String resolveReflectionTier(String goalId, String taskId, Skill skill) {
        TaskReflectionState state = resolveTaskReflectionState(goalId, taskId);
        if (state.taskReflectionModelTier() != null && !state.taskReflectionModelTier().isBlank()
                && state.taskReflectionTierPriority()) {
            return state.taskReflectionModelTier();
        }
        if (skill != null && skill.getReflectionTier() != null && !skill.getReflectionTier().isBlank()) {
            return skill.getReflectionTier();
        }
        if (state.taskReflectionModelTier() != null && !state.taskReflectionModelTier().isBlank()) {
            return state.taskReflectionModelTier();
        }
        if (state.goalReflectionModelTier() != null && !state.goalReflectionModelTier().isBlank()) {
            return state.goalReflectionModelTier();
        }
        return runtimeConfigService.getAutoReflectionModelTier();
    }

    public String resolveReflectionTier(String goalId, String taskId) {
        return resolveReflectionTier(goalId, taskId, null);
    }

    public boolean isReflectionTierPriority(String goalId, String taskId) {
        TaskReflectionState state = resolveTaskReflectionState(goalId, taskId);
        if (state.taskReflectionModelTier() != null && !state.taskReflectionModelTier().isBlank()) {
            return state.taskReflectionTierPriority();
        }
        if (state.goalReflectionModelTier() != null && !state.goalReflectionModelTier().isBlank()) {
            return state.goalReflectionTierPriority();
        }
        return runtimeConfigService.isAutoReflectionTierPriority();
    }

    public int getReflectionFailureThreshold() {
        return Math.max(1, runtimeConfigService.getAutoReflectionFailureThreshold());
    }

    // ==================== Diary ====================

    public void writeDiary(DiaryEntry entry) {
        if (entry.getTimestamp() == null) {
            entry.setTimestamp(Instant.now());
        }
        try {
            String date = LocalDate.ofInstant(entry.getTimestamp(), ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            String path = "diary/" + date + ".jsonl";
            String line = objectMapper.writeValueAsString(entry) + "\n";
            storagePort.appendText(AUTO_DIR, path, line).join();
            log.debug("[AutoMode] Diary entry written");
        } catch (Exception e) {
            log.error("[AutoMode] Failed to write diary", e);
        }
    }

    public List<DiaryEntry> getRecentDiary(int count) {
        try {
            List<DiaryEntry> entries = new ArrayList<>();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            for (int i = 0; i < 7 && entries.size() < count; i++) {
                String date = today.minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE);
                String path = "diary/" + date + ".jsonl";
                String content = storagePort.getText(AUTO_DIR, path).join();
                if (content != null && !content.isBlank()) {
                    String[] lines = content.split("\n");
                    for (int j = lines.length - 1; j >= 0 && entries.size() < count; j--) {
                        if (!lines[j].isBlank()) {
                            entries.add(objectMapper.readValue(lines[j], DiaryEntry.class));
                        }
                    }
                }
            }

            Collections.reverse(entries);
            return entries;
        } catch (IOException | RuntimeException e) {
            log.error("[AutoMode] Failed to read diary", e);
            return List.of();
        }
    }

    // ==================== Context building ====================

    public String buildAutoContext() {
        List<Goal> activeGoals = getActiveGoals();
        if (activeGoals.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Auto Mode\n\n");

        sb.append("## Active Goals\n");
        for (int i = 0; i < activeGoals.size(); i++) {
            Goal goal = activeGoals.get(i);
            long completed = goal.getCompletedTaskCount();
            int total = goal.getTasks().size();
            sb.append(String.format("%d. **%s** [%s] (%d/%d tasks done)%n",
                    i + 1, goal.getTitle(), goal.getStatus(),
                    completed, total));
            if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
                sb.append("   Description: ").append(goal.getDescription()).append("\n");
            }
            if (goal.getPrompt() != null && !goal.getPrompt().isBlank()) {
                sb.append("   Prompt: ").append(goal.getPrompt()).append("\n");
            }
            if (goal.getReflectionModelTier() != null && !goal.getReflectionModelTier().isBlank()) {
                sb.append("   Reflection tier: ").append(goal.getReflectionModelTier())
                        .append(goal.isReflectionTierPriority() ? " (priority)" : " (default)")
                        .append("\n");
            }
        }

        Optional<AutoTask> nextTask = getNextPendingTask();
        if (nextTask.isPresent()) {
            AutoTask task = nextTask.get();
            sb.append("\n## Current Task\n");
            String goalTitle = activeGoals.stream()
                    .filter(g -> g.getId().equals(task.getGoalId()))
                    .map(Goal::getTitle)
                    .findFirst()
                    .orElse("unknown");
            sb.append(String.format("**%s** (goal: %s)%n", task.getTitle(), goalTitle));
            sb.append("Status: ").append(task.getStatus()).append("\n");
            if (task.getDescription() != null && !task.getDescription().isBlank()) {
                sb.append("Details: ").append(task.getDescription()).append("\n");
            }
            if (task.getPrompt() != null && !task.getPrompt().isBlank()) {
                sb.append("Prompt: ").append(task.getPrompt()).append("\n");
            }
            if (task.getReflectionModelTier() != null && !task.getReflectionModelTier().isBlank()) {
                sb.append("Reflection tier: ").append(task.getReflectionModelTier())
                        .append(task.isReflectionTierPriority() ? " (priority)" : " (default)")
                        .append("\n");
            }
            if (task.getReflectionStrategy() != null && !task.getReflectionStrategy().isBlank()) {
                sb.append("Recovery strategy: ").append(task.getReflectionStrategy()).append("\n");
            }
            if (task.isReflectionRequired()) {
                sb.append("Reflection required after repeated failures.\n");
            }
        }

        int maxEntries = 10;
        List<DiaryEntry> recentDiary = getRecentDiary(maxEntries);
        if (!recentDiary.isEmpty()) {
            sb.append("\n## Recent Diary (last ").append(recentDiary.size()).append(")\n");
            DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneOffset.UTC);
            for (DiaryEntry entry : recentDiary) {
                sb.append("- [").append(timeFormat.format(entry.getTimestamp())).append("] ");
                sb.append(entry.getType()).append(": ");
                sb.append(entry.getContent()).append("\n");
            }
        }

        sb.append("\n## Instructions\n");
        sb.append("You are in autonomous work mode.\n");
        sb.append("1. Work on the current task above using available tools\n");
        sb.append("2. Use goal_management tool to update task status when done or write diary entries\n");
        sb.append("3. When all tasks for a goal are done, mark the goal as COMPLETED\n");
        sb.append("4. When a recovery strategy is present, follow it instead of repeating the failed approach\n");
        sb.append("5. If you need to create new sub-tasks, use plan_tasks operation\n");
        sb.append("6. Be concise and focused — record key findings in diary\n");

        return sb.toString();
    }

    // ==================== Persistence ====================

    private void saveState(boolean enabled) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("enabled", enabled));
            storagePort.putText(AUTO_DIR, "state.json", json).join();
        } catch (Exception e) {
            log.error("[AutoMode] Failed to save state", e);
        }
    }

    private void saveGoals(List<Goal> goals) {
        try {
            String json = objectMapper.writeValueAsString(goals);
            storagePort.putText(AUTO_DIR, "goals.json", json).join();
            goalsCache = goals;
        } catch (Exception e) {
            log.error("[AutoMode] Failed to save goals", e);
        }
    }

    private List<Goal> loadGoals() {
        try {
            String json = storagePort.getText(AUTO_DIR, "goals.json").join();
            if (json != null && !json.isBlank()) {
                return new ArrayList<>(objectMapper.readValue(json, GOAL_LIST_TYPE_REF));
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("[AutoMode] No goals found or failed to parse: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Load enabled state from storage. Called externally by scheduler.
     */
    public void loadState() {
        try {
            String json = storagePort.getText(AUTO_DIR, "state.json").join();
            if (json != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> state = objectMapper.readValue(json, Map.class);
                enabled = Boolean.TRUE.equals(state.get("enabled"));
            }
            log.info("[AutoMode] Loaded state: enabled={}", enabled);
        } catch (IOException | RuntimeException e) {
            log.debug("[AutoMode] Failed to load state: {}", e.getMessage());
        }
    }

    /**
     * Find the goal that contains a given task.
     */
    public Optional<Goal> findGoalForTask(String taskId) {
        return getGoals().stream()
                .filter(g -> g.getTasks().stream().anyMatch(t -> t.getId().equals(taskId)))
                .findFirst();
    }

    private Goal resolveTaskGoal(String goalId) {
        if (StringValueSupport.isBlank(goalId)) {
            return getOrCreateInboxGoal();
        }
        return getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
    }

    private void validateTaskCapacity(Goal goal) {
        if (goal.getTasks().size() >= MAX_TASKS_PER_GOAL) {
            throw new IllegalStateException("Maximum tasks per goal reached: " + MAX_TASKS_PER_GOAL);
        }
    }

    private int nextTaskOrder(Goal goal) {
        return goal.getTasks().stream()
                .mapToInt(AutoTask::getOrder)
                .max()
                .orElse(0) + 1;
    }

    private void rebalanceTaskOrders(Goal goal) {
        List<AutoTask> orderedTasks = new ArrayList<>(goal.getTasks());
        orderedTasks.sort(Comparator.comparingInt(AutoTask::getOrder)
                .thenComparing(AutoTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        for (int index = 0; index < orderedTasks.size(); index++) {
            orderedTasks.get(index).setOrder(index + 1);
        }
    }

    private Optional<TaskLocation> findTaskLocation(String taskId) {
        for (Goal goal : getGoals()) {
            for (AutoTask task : goal.getTasks()) {
                if (task.getId().equals(taskId)) {
                    return Optional.of(new TaskLocation(goal, task));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<TaskReflectionState> getTaskReflectionState(String goalId, String taskId) {
        if (StringValueSupport.isBlank(goalId)) {
            return Optional.empty();
        }

        return getGoal(goalId).flatMap(goal -> {
            if (StringValueSupport.isBlank(taskId)) {
                return Optional.of(new TaskReflectionState(
                        goal.getReflectionModelTier(),
                        goal.isReflectionTierPriority(),
                        null,
                        false,
                        goal.getLastUsedSkillName(),
                        goal.getConsecutiveFailureCount(),
                        goal.isReflectionRequired(),
                        goal.getLastFailureSummary(),
                        goal.getLastFailureFingerprint(),
                        goal.getReflectionStrategy()));
            }

            return goal.getTasks().stream()
                    .filter(item -> taskId.equals(item.getId()))
                    .findFirst()
                    .map(task -> new TaskReflectionState(
                            goal.getReflectionModelTier(),
                            goal.isReflectionTierPriority(),
                            task.getReflectionModelTier(),
                            task.isReflectionTierPriority(),
                            task.getLastUsedSkillName(),
                            task.getConsecutiveFailureCount(),
                            task.isReflectionRequired(),
                            task.getLastFailureSummary(),
                            task.getLastFailureFingerprint(),
                            task.getReflectionStrategy()));
        });
    }

    private String normalizeOptionalValue(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private String requireTitle(String value, String message) {
        if (StringValueSupport.isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private void resetTaskFailureState(AutoTask task) {
        task.setConsecutiveFailureCount(0);
        task.setReflectionRequired(false);
        task.setLastFailureSummary(null);
        task.setLastFailureFingerprint(null);
        task.setLastFailureAt(null);
    }

    private void resetGoalFailureState(Goal goal) {
        goal.setConsecutiveFailureCount(0);
        goal.setReflectionRequired(false);
        goal.setLastFailureSummary(null);
        goal.setLastFailureFingerprint(null);
        goal.setLastFailureAt(null);
    }

    private String buildFailureFingerprint(String failureSummary) {
        if (StringValueSupport.isBlank(failureSummary)) {
            return null;
        }
        return failureSummary.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public record TaskReflectionState(
            String goalReflectionModelTier,
            boolean goalReflectionTierPriority,
            String taskReflectionModelTier,
            boolean taskReflectionTierPriority,
            String lastUsedSkillName,
            int consecutiveFailureCount,
            boolean reflectionRequired,
            String lastFailureSummary,
            String lastFailureFingerprint,
            String reflectionStrategy) {
    }

    private record TaskLocation(Goal goal, AutoTask task) {
    }
}
