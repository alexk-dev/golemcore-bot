package me.golemcore.bot.domain.session;

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

import me.golemcore.bot.domain.support.StringValueSupport;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import org.springframework.stereotype.Service;

/**
 * Business operations for session-scoped goals/tasks.
 */
@Service
@Slf4j
public class SessionScopedGoalService {

    private static final String GOAL_NOT_FOUND = "Goal not found: ";
    private static final String TASK_NOT_FOUND = "Task not found: ";
    private static final String TASK_TITLE_REQUIRED = "Task title is required";
    private static final String INBOX_GOAL_ID = "inbox";
    private static final String INBOX_GOAL_TITLE = "Inbox";
    private static final String INBOX_GOAL_DESCRIPTION = "System container for standalone tasks";
    private static final int AUTO_CONTEXT_MAX_OTHER_GOALS = 3;
    private static final int AUTO_CONTEXT_DIARY_LOOKBACK = 20;
    private static final int AUTO_CONTEXT_MAX_DIARY_ENTRIES = 4;
    private static final int AUTO_CONTEXT_FIELD_MAX_CHARS = 1_200;
    private static final int AUTO_CONTEXT_DIARY_MAX_CHARS = 320;
    private static final String AUTO_CONTEXT_TRUNCATED_SUFFIX = " ... [truncated]";

    private final SessionGoalStorageService sessionGoalStorageService;
    private final RuntimeConfigService runtimeConfigService;
    private final SessionDiaryService sessionDiaryService;

    public SessionScopedGoalService(
            SessionGoalStorageService sessionGoalStorageService,
            RuntimeConfigService runtimeConfigService,
            SessionDiaryService sessionDiaryService) {
        this.sessionGoalStorageService = sessionGoalStorageService;
        this.runtimeConfigService = runtimeConfigService;
        this.sessionDiaryService = sessionDiaryService;
    }

    public Goal createGoal(String sessionId, String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        List<Goal> goals = getGoals(sessionId);
        Instant now = Instant.now();
        Goal goal = Goal.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
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
        saveGoals(sessionId, goals);
        return goal;
    }

    public Goal updateGoal(String sessionId, String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, Goal.GoalStatus status) {
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        if (isInboxGoal(goal)) {
            throw new IllegalArgumentException("Inbox goal cannot be edited");
        }

        Goal.GoalStatus resolvedStatus = status != null ? status : goal.getStatus();

        goal.setTitle(requireTitle(title, "Goal title is required"));
        goal.setDescription(normalizeOptionalValue(description));
        goal.setPrompt(normalizeOptionalValue(prompt));
        goal.setReflectionModelTier(normalizeOptionalValue(reflectionModelTier));
        if (reflectionTierPriority != null) {
            goal.setReflectionTierPriority(reflectionTierPriority);
        }
        goal.setStatus(resolvedStatus);
        goal.setUpdatedAt(Instant.now());
        saveGoals(sessionId, goals);
        return goal;
    }

    public List<Goal> getGoals(String sessionId) {
        List<Goal> goals = sessionGoalStorageService.loadGoals(sessionId);
        normalizeGoals(sessionId, goals);
        return goals;
    }

    public void replaceGoals(String sessionId, List<Goal> goals) {
        List<Goal> normalizedGoals = goals != null ? new ArrayList<>(goals) : new ArrayList<>();
        normalizeGoals(sessionId, normalizedGoals);
        saveGoals(sessionId, normalizedGoals);
    }

    public List<Goal> getActiveGoals(String sessionId) {
        return getGoals(sessionId).stream()
                .filter(goal -> goal.getStatus() == Goal.GoalStatus.ACTIVE)
                .toList();
    }

    public Optional<Goal> getGoal(String sessionId, String goalId) {
        return getGoals(sessionId).stream()
                .filter(goal -> goalId.equals(goal.getId()))
                .findFirst();
    }

    public AutoTask addTask(String sessionId, String goalId, String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority, int order) {
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        Instant now = Instant.now();
        AutoTask task = AutoTask.builder()
                .id(UUID.randomUUID().toString())
                .goalId(goalId)
                .title(requireTitle(title, TASK_TITLE_REQUIRED))
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
        saveGoals(sessionId, goals);
        return task;
    }

    public AutoTask createTask(String sessionId, String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, AutoTask.TaskStatus status) {
        List<Goal> goals = getGoals(sessionId);
        Goal targetGoal = resolveTaskGoal(sessionId, goals, goalId);

        Instant now = Instant.now();
        AutoTask task = AutoTask.builder()
                .id(UUID.randomUUID().toString())
                .goalId(targetGoal.getId())
                .title(requireTitle(title, TASK_TITLE_REQUIRED))
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
        saveGoals(sessionId, goals);
        return task;
    }

    public boolean isInboxGoal(Goal goal) {
        if (goal == null || goal.getId() == null) {
            return false;
        }
        return goal.isSystemInbox() || INBOX_GOAL_ID.equals(goal.getId());
    }

    public Goal getOrCreateInboxGoal(String sessionId) {
        List<Goal> goals = getGoals(sessionId);
        Optional<Goal> existing = findGoal(goals, INBOX_GOAL_ID);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        Goal inboxGoal = Goal.builder()
                .id(INBOX_GOAL_ID)
                .sessionId(sessionId)
                .title(INBOX_GOAL_TITLE)
                .description(INBOX_GOAL_DESCRIPTION)
                .systemInbox(true)
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        goals.add(inboxGoal);
        saveGoals(sessionId, goals);
        return inboxGoal;
    }

    public Optional<Goal> findGoalForTask(String sessionId, String taskId) {
        return getGoals(sessionId).stream()
                .filter(goal -> goal.getTasks().stream().anyMatch(task -> taskId.equals(task.getId())))
                .findFirst();
    }

    public Optional<AutoTask> getTask(String sessionId, String taskId) {
        return findTaskLocation(getGoals(sessionId), taskId).map(TaskLocation::task);
    }

    public AutoTask updateTask(String sessionId, String taskId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, AutoTask.TaskStatus status) {
        List<Goal> goals = getGoals(sessionId);
        TaskLocation location = findTaskLocation(goals, taskId)
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        Goal goal = location.goal();
        AutoTask task = location.task();
        AutoTask.TaskStatus previousStatus = task.getStatus();
        AutoTask.TaskStatus resolvedStatus = status != null ? status : task.getStatus();
        Instant now = Instant.now();

        task.setTitle(requireTitle(title, TASK_TITLE_REQUIRED));
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
        saveGoals(sessionId, goals);
        if (resolvedStatus == AutoTask.TaskStatus.COMPLETED && previousStatus != AutoTask.TaskStatus.COMPLETED) {
            sessionDiaryService.writeDiary(sessionId, DiaryEntry.builder()
                    .timestamp(now)
                    .type(DiaryEntry.DiaryType.PROGRESS)
                    .content("Completed task: " + task.getTitle())
                    .goalId(goal.getId())
                    .taskId(taskId)
                    .build());
        }
        return task;
    }

    public Optional<AutoTask> getNextPendingTask(String sessionId) {
        return getActiveGoals(sessionId).stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .flatMap(goal -> goal.getTasks().stream()
                        .filter(task -> task.getStatus() == AutoTask.TaskStatus.PENDING)
                        .sorted(Comparator.comparingInt(AutoTask::getOrder)))
                .findFirst();
    }

    public void updateTaskStatus(String sessionId, String goalId, String taskId, AutoTask.TaskStatus status,
            String result) {
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        Instant now = Instant.now();
        task.setStatus(status);
        task.setResult(normalizeOptionalValue(result));
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

        saveGoals(sessionId, goals);
        if (status == AutoTask.TaskStatus.COMPLETED) {
            sessionDiaryService.writeDiary(sessionId, DiaryEntry.builder()
                    .timestamp(now)
                    .type(DiaryEntry.DiaryType.PROGRESS)
                    .content("Completed task: " + task.getTitle()
                            + (result != null && !result.isBlank() ? " - " + result : ""))
                    .goalId(goalId)
                    .taskId(taskId)
                    .build());
        }
    }

    public void completeGoal(String sessionId, String goalId) {
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        goal.setStatus(Goal.GoalStatus.COMPLETED);
        goal.setUpdatedAt(Instant.now());
        saveGoals(sessionId, goals);
        sessionDiaryService.writeDiary(sessionId, DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.PROGRESS)
                .content("Goal completed: " + goal.getTitle())
                .goalId(goalId)
                .build());
    }

    public void deleteGoal(String sessionId, String goalId) {
        if (INBOX_GOAL_ID.equals(goalId)) {
            throw new IllegalArgumentException("Inbox goal cannot be deleted");
        }
        List<Goal> goals = getGoals(sessionId);
        boolean removed = goals.removeIf(goal -> goalId.equals(goal.getId()));
        if (!removed) {
            throw new IllegalArgumentException(GOAL_NOT_FOUND + goalId);
        }
        saveGoals(sessionId, goals);
        sessionDiaryService.writeDiary(sessionId, DiaryEntry.builder()
                .timestamp(Instant.now())
                .type(DiaryEntry.DiaryType.DECISION)
                .content("Goal deleted: " + goalId)
                .goalId(goalId)
                .build());
    }

    public void deleteTask(String sessionId, String goalId, String taskId) {
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        boolean removed = goal.getTasks().removeIf(task -> taskId.equals(task.getId()));
        if (!removed) {
            throw new IllegalArgumentException(TASK_NOT_FOUND + taskId);
        }
        goal.setUpdatedAt(Instant.now());
        rebalanceTaskOrders(goal);
        saveGoals(sessionId, goals);
    }

    public void deleteTask(String sessionId, String taskId) {
        TaskLocation location = findTaskLocation(getGoals(sessionId), taskId)
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        deleteTask(sessionId, location.goal().getId(), taskId);
    }

    public int clearCompletedGoals(String sessionId) {
        List<Goal> goals = getGoals(sessionId);
        int before = goals.size();
        goals.removeIf(goal -> !isInboxGoal(goal)
                && (goal.getStatus() == Goal.GoalStatus.COMPLETED || goal.getStatus() == Goal.GoalStatus.CANCELLED));
        int removed = before - goals.size();
        if (removed > 0) {
            saveGoals(sessionId, goals);
        }
        return removed;
    }

    public void recordAutoRunFailure(String sessionId, String goalId, String taskId, String failureSummary,
            String failureFingerprint, String activeSkillName) {
        if (StringValueSupport.isBlank(goalId)) {
            return;
        }
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        Instant now = Instant.now();
        String normalizedSummary = normalizeOptionalValue(failureSummary);
        String normalizedFingerprint = buildFailureFingerprint(failureFingerprint != null ? failureFingerprint
                : failureSummary);
        String normalizedSkillName = normalizeOptionalValue(activeSkillName);
        if (StringValueSupport.isBlank(taskId)) {
            goal.setConsecutiveFailureCount(goal.getConsecutiveFailureCount() + 1);
            goal.setLastFailureSummary(normalizedSummary);
            goal.setLastFailureFingerprint(normalizedFingerprint);
            goal.setLastFailureAt(now);
            goal.setLastUsedSkillName(normalizedSkillName);
            goal.setReflectionRequired(goal.getConsecutiveFailureCount() >= getReflectionFailureThreshold());
            goal.setUpdatedAt(now);
            saveGoals(sessionId, goals);
            return;
        }
        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        task.setStatus(AutoTask.TaskStatus.FAILED);
        task.setResult(normalizedSummary);
        task.setConsecutiveFailureCount(task.getConsecutiveFailureCount() + 1);
        task.setLastFailureSummary(normalizedSummary);
        task.setLastFailureFingerprint(normalizedFingerprint);
        task.setLastFailureAt(now);
        task.setLastUsedSkillName(normalizedSkillName);
        task.setReflectionRequired(task.getConsecutiveFailureCount() >= getReflectionFailureThreshold());
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);
        saveGoals(sessionId, goals);
    }

    public void recordAutoRunSuccess(String sessionId, String goalId, String taskId, String activeSkillName) {
        if (StringValueSupport.isBlank(goalId)) {
            return;
        }
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        String normalizedSkillName = normalizeOptionalValue(activeSkillName);
        Instant now = Instant.now();
        if (StringValueSupport.isBlank(taskId)) {
            resetGoalFailureState(goal);
            goal.setLastUsedSkillName(normalizedSkillName);
            goal.setUpdatedAt(now);
            saveGoals(sessionId, goals);
            return;
        }
        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        resetTaskFailureState(task);
        task.setLastUsedSkillName(normalizedSkillName);
        if (task.getStatus() == AutoTask.TaskStatus.FAILED) {
            task.setStatus(AutoTask.TaskStatus.IN_PROGRESS);
        }
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);
        saveGoals(sessionId, goals);
    }

    public boolean shouldTriggerReflection(String sessionId, String goalId, String taskId) {
        return resolveTaskReflectionState(sessionId, goalId, taskId).reflectionRequired();
    }

    public void applyReflectionResult(String sessionId, String goalId, String taskId, String reflectionStrategy) {
        if (StringValueSupport.isBlank(goalId)) {
            return;
        }
        List<Goal> goals = getGoals(sessionId);
        Goal goal = findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
        Instant now = Instant.now();
        String normalizedStrategy = normalizeOptionalValue(reflectionStrategy);
        if (StringValueSupport.isBlank(taskId)) {
            goal.setReflectionStrategy(normalizedStrategy);
            goal.setReflectionRequired(false);
            goal.setConsecutiveFailureCount(0);
            goal.setLastReflectionAt(now);
            goal.setUpdatedAt(now);
            saveGoals(sessionId, goals);
            return;
        }
        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND + taskId));
        task.setReflectionStrategy(normalizedStrategy);
        task.setReflectionRequired(false);
        task.setConsecutiveFailureCount(0);
        task.setLastReflectionAt(now);
        if (task.getStatus() == AutoTask.TaskStatus.FAILED) {
            task.setStatus(AutoTask.TaskStatus.IN_PROGRESS);
        }
        task.setUpdatedAt(now);
        goal.setUpdatedAt(now);
        saveGoals(sessionId, goals);
    }

    public TaskReflectionStateSnapshot resolveTaskReflectionState(String sessionId, String goalId, String taskId) {
        if (StringValueSupport.isBlank(goalId)) {
            return TaskReflectionStateSnapshot.empty();
        }
        Goal goal = getGoal(sessionId, goalId).orElse(null);
        if (goal == null) {
            return TaskReflectionStateSnapshot.empty();
        }
        if (StringValueSupport.isBlank(taskId)) {
            return new TaskReflectionStateSnapshot(
                    goal.getReflectionModelTier(),
                    goal.isReflectionTierPriority(),
                    null,
                    false,
                    goal.getLastUsedSkillName(),
                    goal.getConsecutiveFailureCount(),
                    goal.isReflectionRequired(),
                    goal.getLastFailureSummary(),
                    goal.getLastFailureFingerprint(),
                    goal.getReflectionStrategy());
        }
        AutoTask task = goal.getTasks().stream()
                .filter(item -> taskId.equals(item.getId()))
                .findFirst()
                .orElse(null);
        if (task == null) {
            return TaskReflectionStateSnapshot.empty();
        }
        return new TaskReflectionStateSnapshot(
                goal.getReflectionModelTier(),
                goal.isReflectionTierPriority(),
                task.getReflectionModelTier(),
                task.isReflectionTierPriority(),
                task.getLastUsedSkillName(),
                task.getConsecutiveFailureCount(),
                task.isReflectionRequired(),
                task.getLastFailureSummary(),
                task.getLastFailureFingerprint(),
                task.getReflectionStrategy());
    }

    public int getReflectionFailureThreshold() {
        return Math.max(1, runtimeConfigService.getAutoReflectionFailureThreshold());
    }

    public String buildAutoContext(String sessionId) {
        return buildAutoContext(sessionId, null, null);
    }

    public String buildAutoContext(String sessionId, String requestedGoalId, String requestedTaskId) {
        List<Goal> activeGoals = getActiveGoals(sessionId);
        if (activeGoals.isEmpty()) {
            return null;
        }

        Optional<AutoTask> currentTask = resolveCurrentTask(sessionId, requestedTaskId);
        Optional<Goal> currentGoal = resolveCurrentGoal(activeGoals, requestedGoalId, currentTask);

        StringBuilder sb = new StringBuilder();
        sb.append("# Auto Mode\n\n");

        currentGoal.ifPresent(goal -> appendCurrentGoal(sb, goal));
        String relevanceText = buildAutoRelevanceText(currentGoal.orElse(null), currentTask.orElse(null));
        appendOtherActiveGoals(sb, activeGoals, currentGoal.map(Goal::getId).orElse(null), relevanceText);

        currentTask.ifPresent(task -> appendCurrentTask(sb, task, currentGoal.map(Goal::getTitle).orElse("unknown")));
        appendRelevantDiary(sb, selectRelevantDiary(
                sessionId,
                currentGoal.map(Goal::getId).orElse(requestedGoalId),
                currentTask.map(AutoTask::getId).orElse(requestedTaskId),
                relevanceText));

        sb.append("\n## Instructions\n");
        sb.append("You are in autonomous work mode.\n");
        sb.append("1. Work on the current task above using available tools\n");
        sb.append("2. Use goal_management tool to update task status when done or write diary entries\n");
        sb.append("3. When all tasks for a goal are done, mark the goal as COMPLETED\n");
        sb.append("4. When a recovery strategy is present, follow it instead of repeating the failed approach\n");
        sb.append("5. If you need to create new sub-tasks, use plan_tasks operation\n");
        sb.append("6. Be concise and focused - record key findings in diary\n");
        return sb.toString();
    }

    private Optional<AutoTask> resolveCurrentTask(String sessionId, String requestedTaskId) {
        if (!StringValueSupport.isBlank(requestedTaskId)) {
            return getTask(sessionId, requestedTaskId);
        }
        return getNextPendingTask(sessionId);
    }

    private Optional<Goal> resolveCurrentGoal(List<Goal> activeGoals, String requestedGoalId,
            Optional<AutoTask> currentTask) {
        if (!StringValueSupport.isBlank(requestedGoalId)) {
            return activeGoals.stream()
                    .filter(goal -> requestedGoalId.equals(goal.getId()))
                    .findFirst();
        }
        if (currentTask.isPresent()) {
            String taskGoalId = currentTask.get().getGoalId();
            return activeGoals.stream()
                    .filter(goal -> !StringValueSupport.isBlank(taskGoalId) && taskGoalId.equals(goal.getId()))
                    .findFirst();
        }
        return activeGoals.stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst();
    }

    private void appendCurrentGoal(StringBuilder sb, Goal goal) {
        long completed = goal.getCompletedTaskCount();
        int total = goal.getTasks().size();
        sb.append("## Current Goal\n");
        sb.append(String.format("**%s** [%s] (%d/%d tasks done)%n",
                goal.getTitle(), goal.getStatus(), completed, total));
        appendField(sb, "Description", goal.getDescription(), AUTO_CONTEXT_FIELD_MAX_CHARS);
        appendField(sb, "Prompt", goal.getPrompt(), AUTO_CONTEXT_FIELD_MAX_CHARS);
        appendReflectionTier(sb, goal.getReflectionModelTier(), goal.isReflectionTierPriority());
        appendField(sb, "Recovery strategy", goal.getReflectionStrategy(), AUTO_CONTEXT_FIELD_MAX_CHARS);
    }

    private void appendOtherActiveGoals(StringBuilder sb, List<Goal> activeGoals, String currentGoalId,
            String relevanceText) {
        List<Goal> otherGoals = activeGoals.stream()
                .filter(goal -> currentGoalId == null || !currentGoalId.equals(goal.getId()))
                .sorted(Comparator.comparingInt((Goal goal) -> scoreText(goalSearchText(goal), relevanceText))
                        .reversed()
                        .thenComparing(Goal::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(AUTO_CONTEXT_MAX_OTHER_GOALS)
                .toList();
        if (otherGoals.isEmpty()) {
            return;
        }
        sb.append("\n## Other Active Goals (summary)\n");
        for (Goal goal : otherGoals) {
            sb.append("- ").append(goal.getTitle()).append(" [").append(goal.getStatus()).append("] (")
                    .append(goal.getCompletedTaskCount()).append("/").append(goal.getTasks().size())
                    .append(" tasks done)\n");
        }
        long omitted = activeGoals.stream()
                .filter(goal -> currentGoalId == null || !currentGoalId.equals(goal.getId()))
                .count() - otherGoals.size();
        if (omitted > 0) {
            sb.append("- ... ").append(omitted).append(" more active goals omitted from prompt\n");
        }
    }

    private void appendCurrentTask(StringBuilder sb, AutoTask task, String goalTitle) {
        sb.append("\n## Current Task\n");
        sb.append(String.format("**%s** (goal: %s)%n", task.getTitle(), goalTitle));
        sb.append("Status: ").append(task.getStatus()).append("\n");
        appendField(sb, "Details", task.getDescription(), AUTO_CONTEXT_FIELD_MAX_CHARS);
        appendField(sb, "Prompt", task.getPrompt(), AUTO_CONTEXT_FIELD_MAX_CHARS);
        appendReflectionTier(sb, task.getReflectionModelTier(), task.isReflectionTierPriority());
        appendField(sb, "Recovery strategy", task.getReflectionStrategy(), AUTO_CONTEXT_FIELD_MAX_CHARS);
        if (task.isReflectionRequired()) {
            sb.append("Reflection required after repeated failures.\n");
        }
    }

    private List<DiaryEntry> selectRelevantDiary(String sessionId, String goalId, String taskId, String relevanceText) {
        List<DiaryEntry> recentDiary = sessionDiaryService.getRecentDiary(sessionId, AUTO_CONTEXT_DIARY_LOOKBACK);
        List<ScoredDiaryEntry> scored = new ArrayList<>();
        for (int index = 0; index < recentDiary.size(); index++) {
            DiaryEntry entry = recentDiary.get(index);
            int score = scoreDiary(entry, goalId, taskId, relevanceText) + index;
            if (score > 0) {
                scored.add(new ScoredDiaryEntry(entry, index, score));
            }
        }
        List<ScoredDiaryEntry> selected = scored.stream()
                .sorted(Comparator.comparingInt(ScoredDiaryEntry::score).reversed()
                        .thenComparing(ScoredDiaryEntry::index, Comparator.reverseOrder()))
                .limit(AUTO_CONTEXT_MAX_DIARY_ENTRIES)
                .sorted(Comparator.comparingInt(ScoredDiaryEntry::index))
                .toList();
        if (!selected.isEmpty()) {
            return selected.stream().map(ScoredDiaryEntry::entry).toList();
        }
        int fromIndex = Math.max(0, recentDiary.size() - AUTO_CONTEXT_MAX_DIARY_ENTRIES);
        return recentDiary.subList(fromIndex, recentDiary.size());
    }

    private void appendRelevantDiary(StringBuilder sb, List<DiaryEntry> diaryEntries) {
        if (diaryEntries.isEmpty()) {
            return;
        }
        sb.append("\n## Relevant Diary (").append(diaryEntries.size()).append(" entries)\n");
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneOffset.UTC);
        for (DiaryEntry entry : diaryEntries) {
            sb.append("- [").append(timeFormat.format(entry.getTimestamp())).append("] ");
            sb.append(entry.getType()).append(": ");
            sb.append(truncate(entry.getContent(), AUTO_CONTEXT_DIARY_MAX_CHARS)).append("\n");
        }
        sb.append("Older or unrelated diary entries are intentionally omitted from the system prompt.\n");
    }

    private int scoreDiary(DiaryEntry entry, String goalId, String taskId, String relevanceText) {
        if (entry == null) {
            return 0;
        }
        int score = 0;
        if (!StringValueSupport.isBlank(taskId) && taskId.equals(entry.getTaskId())) {
            score += 100;
        }
        if (!StringValueSupport.isBlank(goalId) && goalId.equals(entry.getGoalId())) {
            score += 60;
        }
        score += scoreText(entry.getContent(), relevanceText);
        return score;
    }

    private String buildAutoRelevanceText(Goal goal, AutoTask task) {
        StringBuilder sb = new StringBuilder();
        if (goal != null) {
            sb.append(goalSearchText(goal)).append(' ');
        }
        if (task != null) {
            appendSearchText(sb, task.getTitle());
            appendSearchText(sb, task.getDescription());
            appendSearchText(sb, task.getPrompt());
            appendSearchText(sb, task.getReflectionStrategy());
        }
        return sb.toString();
    }

    private String goalSearchText(Goal goal) {
        if (goal == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendSearchText(sb, goal.getTitle());
        appendSearchText(sb, goal.getDescription());
        appendSearchText(sb, goal.getPrompt());
        appendSearchText(sb, goal.getReflectionStrategy());
        return sb.toString();
    }

    private int scoreText(String text, String relevanceText) {
        if (StringValueSupport.isBlank(text) || StringValueSupport.isBlank(relevanceText)) {
            return 0;
        }
        Set<String> terms = tokenize(relevanceText);
        if (terms.isEmpty()) {
            return 0;
        }
        Set<String> textTerms = tokenize(text);
        int score = 0;
        for (String term : terms) {
            if (textTerms.contains(term)) {
                score += 6;
            }
        }
        return score;
    }

    private Set<String> tokenize(String value) {
        if (StringValueSupport.isBlank(value)) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 3) {
                terms.add(token);
            }
        }
        return terms;
    }

    private void appendSearchText(StringBuilder sb, String value) {
        if (!StringValueSupport.isBlank(value)) {
            sb.append(value).append(' ');
        }
    }

    private record ScoredDiaryEntry(DiaryEntry entry, int index, int score) {
    }

    private void appendReflectionTier(StringBuilder sb, String reflectionModelTier, boolean reflectionTierPriority) {
        if (StringValueSupport.isBlank(reflectionModelTier)) {
            return;
        }
        sb.append("Reflection tier: ").append(reflectionModelTier)
                .append(reflectionTierPriority ? " (priority)" : " (default)")
                .append("\n");
    }

    private void appendField(StringBuilder sb, String label, String value, int maxChars) {
        if (StringValueSupport.isBlank(value)) {
            return;
        }
        sb.append(label).append(": ").append(truncate(value, maxChars)).append("\n");
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        int contentLimit = Math.max(0, maxChars - AUTO_CONTEXT_TRUNCATED_SUFFIX.length());
        return value.substring(0, contentLimit).stripTrailing() + AUTO_CONTEXT_TRUNCATED_SUFFIX;
    }

    private void saveGoals(String sessionId, List<Goal> goals) {
        normalizeGoals(sessionId, goals);
        sessionGoalStorageService.saveGoals(sessionId, goals);
    }

    private void normalizeGoals(String sessionId, List<Goal> goals) {
        if (goals == null) {
            return;
        }
        for (Goal goal : goals) {
            normalizeGoal(sessionId, goal);
        }
    }

    private void normalizeGoal(String sessionId, Goal goal) {
        if (goal == null) {
            return;
        }
        goal.setSessionId(sessionId);
        if (INBOX_GOAL_ID.equals(goal.getId())) {
            normalizeInboxGoal(goal);
        }
        normalizeGoalTasks(goal);
    }

    private void normalizeInboxGoal(Goal goal) {
        goal.setSystemInbox(true);
        if (goal.getTitle() == null) {
            goal.setTitle(INBOX_GOAL_TITLE);
        }
        if (goal.getDescription() == null) {
            goal.setDescription(INBOX_GOAL_DESCRIPTION);
        }
    }

    private void normalizeGoalTasks(Goal goal) {
        if (goal.getTasks() == null) {
            goal.setTasks(new ArrayList<>());
            return;
        }
        for (AutoTask task : goal.getTasks()) {
            if (task != null) {
                task.setGoalId(goal.getId());
            }
        }
    }

    private Optional<Goal> findGoal(List<Goal> goals, String goalId) {
        return goals.stream().filter(goal -> goalId.equals(goal.getId())).findFirst();
    }

    private Goal resolveTaskGoal(String sessionId, List<Goal> goals, String goalId) {
        if (StringValueSupport.isBlank(goalId)) {
            Optional<Goal> existingInbox = findGoal(goals, INBOX_GOAL_ID);
            if (existingInbox.isPresent()) {
                return existingInbox.get();
            }
            Instant now = Instant.now();
            Goal inboxGoal = Goal.builder()
                    .id(INBOX_GOAL_ID)
                    .sessionId(sessionId)
                    .title(INBOX_GOAL_TITLE)
                    .description(INBOX_GOAL_DESCRIPTION)
                    .systemInbox(true)
                    .status(Goal.GoalStatus.ACTIVE)
                    .tasks(new ArrayList<>())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            goals.add(inboxGoal);
            return inboxGoal;
        }
        return findGoal(goals, goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));
    }

    private Optional<TaskLocation> findTaskLocation(List<Goal> goals, String taskId) {
        for (Goal goal : goals) {
            for (AutoTask task : goal.getTasks()) {
                if (taskId.equals(task.getId())) {
                    return Optional.of(new TaskLocation(goal, task));
                }
            }
        }
        return Optional.empty();
    }

    private int nextTaskOrder(Goal goal) {
        return goal.getTasks().stream().mapToInt(AutoTask::getOrder).max().orElse(0) + 1;
    }

    private void rebalanceTaskOrders(Goal goal) {
        List<AutoTask> orderedTasks = new ArrayList<>(goal.getTasks());
        orderedTasks.sort(Comparator.comparingInt(AutoTask::getOrder)
                .thenComparing(AutoTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        for (int index = 0; index < orderedTasks.size(); index++) {
            orderedTasks.get(index).setOrder(index + 1);
        }
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

    private record TaskLocation(Goal goal, AutoTask task) {
    }

    public record TaskReflectionStateSnapshot(
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

        public static TaskReflectionStateSnapshot empty() {
            return new TaskReflectionStateSnapshot(null, false, null, false, null, 0, false, null, null, null);
        }
    }
}
