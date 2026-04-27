package me.golemcore.bot.domain.scheduling;

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

import me.golemcore.bot.domain.service.StringValueSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.ScheduledTaskPersistencePort;
import org.springframework.stereotype.Service;

/**
 * Persistent storage and lifecycle helpers for scheduled tasks.
 */
@Service
@Slf4j
public class PersistentScheduledTaskService {

    private static final String SCHEDULED_TASK_NOT_FOUND = "Scheduled task not found: ";

    private final ScheduledTaskPersistencePort scheduledTaskPersistencePort;

    private List<ScheduledTask> cache;

    public PersistentScheduledTaskService(ScheduledTaskPersistencePort scheduledTaskPersistencePort) {
        this.scheduledTaskPersistencePort = scheduledTaskPersistencePort;
    }

    public synchronized List<ScheduledTask> getScheduledTasks() {
        return List.copyOf(ensureLoaded());
    }

    public synchronized Optional<ScheduledTask> getScheduledTask(String scheduledTaskId) {
        if (StringValueSupport.isBlank(scheduledTaskId)) {
            return Optional.empty();
        }
        return ensureLoaded().stream()
                .filter(task -> scheduledTaskId.equals(task.getId()))
                .findFirst();
    }

    public synchronized ScheduledTask createScheduledTask(
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            boolean reflectionTierPriority) {
        return createScheduledTask(
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                ScheduledTask.ExecutionMode.AGENT_PROMPT,
                null,
                null);
    }

    public synchronized ScheduledTask createScheduledTask(
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            boolean reflectionTierPriority,
            ScheduledTask.ExecutionMode executionMode,
            String shellCommand,
            String shellWorkingDirectory) {
        ScheduledTask task = ScheduledTaskMutationSupport.createTask(
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                executionMode,
                shellCommand,
                shellWorkingDirectory);
        List<ScheduledTask> tasks = ensureLoaded();
        tasks.add(task);
        saveScheduledTasks(tasks);
        return task;
    }

    public synchronized ScheduledTask updateScheduledTask(
            String scheduledTaskId,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority) {
        return updateScheduledTask(
                scheduledTaskId,
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                null,
                null,
                null);
    }

    public synchronized ScheduledTask updateScheduledTask(
            String scheduledTaskId,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority,
            ScheduledTask.ExecutionMode executionMode,
            String shellCommand,
            String shellWorkingDirectory) {
        ScheduledTask task = requireScheduledTask(scheduledTaskId);
        ScheduledTaskMutationSupport.applyUpdate(
                task,
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                executionMode,
                shellCommand,
                shellWorkingDirectory);
        saveScheduledTasks(ensureLoaded());
        return task;
    }

    public synchronized void deleteScheduledTask(String scheduledTaskId) {
        List<ScheduledTask> tasks = ensureLoaded();
        boolean removed = tasks.removeIf(task -> scheduledTaskId.equals(task.getId()));
        if (!removed) {
            throw new IllegalArgumentException(SCHEDULED_TASK_NOT_FOUND + scheduledTaskId);
        }
        saveScheduledTasks(tasks);
    }

    public synchronized ScheduledTask createFromLegacyGoal(Goal goal) {
        if (goal == null) {
            throw new IllegalArgumentException("goal is required");
        }
        Optional<ScheduledTask> existing = findByLegacySource("GOAL", goal.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        ScheduledTask task = ScheduledTaskMutationSupport.createFromLegacyGoal(goal);
        List<ScheduledTask> tasks = ensureLoaded();
        tasks.add(task);
        saveScheduledTasks(tasks);
        return task;
    }

    public synchronized ScheduledTask createFromLegacyTask(Goal goal, AutoTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        Optional<ScheduledTask> existing = findByLegacySource("TASK", task.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        ScheduledTask scheduledTask = ScheduledTaskMutationSupport.createFromLegacyTask(goal, task);
        List<ScheduledTask> tasks = ensureLoaded();
        tasks.add(scheduledTask);
        saveScheduledTasks(tasks);
        return scheduledTask;
    }

    public synchronized void recordFailure(
            String scheduledTaskId,
            String failureSummary,
            String failureFingerprint,
            String activeSkillName,
            int threshold) {
        ScheduledTask task = requireScheduledTask(scheduledTaskId);
        Instant now = Instant.now();
        task.setConsecutiveFailureCount(task.getConsecutiveFailureCount() + 1);
        task.setReflectionRequired(ScheduledTaskMutationSupport.shouldRequireReflection(task, threshold));
        task.setLastFailureSummary(ScheduledTaskMutationSupport.normalizeOptionalValue(failureSummary));
        task.setLastFailureFingerprint(
                ScheduledTaskMutationSupport.resolveFailureFingerprint(failureFingerprint, failureSummary));
        task.setLastFailureAt(now);
        task.setLastUsedSkillName(ScheduledTaskMutationSupport.normalizeOptionalValue(activeSkillName));
        task.setUpdatedAt(now);
        saveScheduledTasks(ensureLoaded());
    }

    public synchronized void recordSuccess(String scheduledTaskId, String activeSkillName) {
        ScheduledTask task = requireScheduledTask(scheduledTaskId);
        Instant now = Instant.now();
        task.setConsecutiveFailureCount(0);
        task.setReflectionRequired(false);
        task.setLastFailureSummary(null);
        task.setLastFailureFingerprint(null);
        task.setLastFailureAt(null);
        task.setLastUsedSkillName(ScheduledTaskMutationSupport.normalizeOptionalValue(activeSkillName));
        task.setUpdatedAt(now);
        saveScheduledTasks(ensureLoaded());
    }

    public synchronized void applyReflectionResult(String scheduledTaskId, String reflectionStrategy) {
        ScheduledTask task = requireScheduledTask(scheduledTaskId);
        Instant now = Instant.now();
        task.setReflectionStrategy(ScheduledTaskMutationSupport.normalizeOptionalValue(reflectionStrategy));
        task.setReflectionRequired(false);
        task.setConsecutiveFailureCount(0);
        task.setLastReflectionAt(now);
        task.setUpdatedAt(now);
        saveScheduledTasks(ensureLoaded());
    }

    public synchronized Optional<ScheduledTask> findByLegacySource(String legacySourceType, String legacySourceId) {
        if (StringValueSupport.isBlank(legacySourceType) || StringValueSupport.isBlank(legacySourceId)) {
            return Optional.empty();
        }
        String normalizedType = legacySourceType.trim();
        String normalizedId = legacySourceId.trim();
        return ensureLoaded().stream()
                .filter(task -> normalizedType.equals(task.getLegacySourceType()))
                .filter(task -> normalizedId.equals(task.getLegacySourceId()))
                .findFirst();
    }

    public List<ScheduledTask> loadScheduledTasks() {
        try {
            List<ScheduledTask> tasks = scheduledTaskPersistencePort.loadScheduledTasks();
            if (tasks == null || tasks.isEmpty()) {
                return new ArrayList<>();
            }
            List<ScheduledTask> normalized = new ArrayList<>(tasks.size());
            for (ScheduledTask task : tasks) {
                ScheduledTask normalizedTask = ScheduledTaskMutationSupport.normalizeLoadedTask(task);
                if (normalizedTask != null) {
                    normalized.add(normalizedTask);
                }
            }
            return normalized;
        } catch (RuntimeException exception) { // NOSONAR
            log.debug("[ScheduledTask] Failed to load scheduled tasks: {}", exception.getMessage());
            throw new IllegalStateException("Failed to load scheduled tasks", exception);
        }
    }

    private ScheduledTask requireScheduledTask(String scheduledTaskId) {
        return getScheduledTask(scheduledTaskId)
                .orElseThrow(() -> new IllegalArgumentException(SCHEDULED_TASK_NOT_FOUND + scheduledTaskId));
    }

    private void saveScheduledTasks(List<ScheduledTask> tasks) {
        try {
            scheduledTaskPersistencePort.replaceScheduledTasks(tasks);
            cache = new ArrayList<>(tasks);
        } catch (RuntimeException exception) { // NOSONAR
            throw new IllegalStateException("Failed to save scheduled tasks", exception);
        }
    }

    private List<ScheduledTask> ensureLoaded() {
        if (cache == null) {
            cache = loadScheduledTasks();
        }
        return cache;
    }
}
