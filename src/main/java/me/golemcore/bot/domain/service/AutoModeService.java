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
import java.util.*;

/**
 * Core service for autonomous agent mode managing goals, tasks, diary entries,
 * and global state. Single-user design with simplified storage layout. Auto
 * mode enables the agent to work independently on long-term goals, breaking
 * them into tasks and maintaining a daily diary of progress. Integrates with
 * {@link auto.AutoModeScheduler} for periodic agent invocations.
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
        List<Goal> goals = getGoals();

        long activeCount = goals.stream()
                .filter(g -> g.getStatus() == Goal.GoalStatus.ACTIVE)
                .count();
        if (activeCount >= runtimeConfigService.getAutoMaxGoals()) {
            throw new IllegalStateException("Maximum active goals reached: " + runtimeConfigService.getAutoMaxGoals());
        }

        Goal goal = Goal.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .description(description)
                .status(Goal.GoalStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        goals.add(goal);
        saveGoals(goals);
        log.info("[AutoMode] Created goal '{}'", title);
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

    public AutoTask addTask(String goalId, String title, String description, int order) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        if (goal.getTasks().size() >= MAX_TASKS_PER_GOAL) {
            throw new IllegalStateException(
                    "Maximum tasks per goal reached: " + MAX_TASKS_PER_GOAL);
        }

        AutoTask task = AutoTask.builder()
                .id(UUID.randomUUID().toString())
                .goalId(goalId)
                .title(title)
                .description(description)
                .order(order)
                .status(AutoTask.TaskStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        goal.getTasks().add(task);
        goal.setUpdatedAt(Instant.now());
        saveGoals(getGoals());
        log.info("[AutoMode] Added task '{}' to goal '{}'", title, goal.getTitle());
        return task;
    }

    public void updateTaskStatus(String goalId, String taskId,
            AutoTask.TaskStatus status, String result) {
        Goal goal = getGoal(goalId)
                .orElseThrow(() -> new IllegalArgumentException(GOAL_NOT_FOUND + goalId));

        AutoTask task = goal.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        task.setStatus(status);
        task.setResult(result);
        task.setUpdatedAt(Instant.now());
        goal.setUpdatedAt(Instant.now());
        saveGoals(getGoals());

        if (status == AutoTask.TaskStatus.COMPLETED) {
            writeDiary(DiaryEntry.builder()
                    .timestamp(Instant.now())
                    .type(DiaryEntry.DiaryType.PROGRESS)
                    .content("Completed task: " + task.getTitle() +
                            (result != null ? " — " + result : ""))
                    .goalId(goalId)
                    .taskId(taskId)
                    .build());
        }

        log.info("[AutoMode] Updated task '{}' status to {}", task.getTitle(), status);
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
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        goal.setUpdatedAt(Instant.now());
        saveGoals(getGoals());
        log.info("[AutoMode] Deleted task '{}' from goal '{}'", taskId, goal.getTitle());
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
        sb.append("4. If you need to create new sub-tasks, use plan_tasks operation\n");
        sb.append("5. Be concise and focused — record key findings in diary\n");

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
}
