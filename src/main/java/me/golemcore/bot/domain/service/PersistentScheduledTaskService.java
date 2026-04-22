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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

/**
 * Persistent storage and lifecycle helpers for scheduled tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersistentScheduledTaskService {

    private static final String AUTO_DIR = "auto";
    private static final String FILE_NAME = "scheduled-tasks.json";
    private static final TypeReference<List<ScheduledTask>> TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;

    private volatile List<ScheduledTask> cache;

    public synchronized List<ScheduledTask> getScheduledTasks() {
        if (cache == null) {
            cache = loadScheduledTasks();
        }
        return cache;
    }

    public Optional<ScheduledTask> getScheduledTask(String scheduledTaskId) {
        if (StringValueSupport.isBlank(scheduledTaskId)) {
            return Optional.empty();
        }
        return getScheduledTasks().stream()
                .filter(task -> scheduledTaskId.equals(task.getId()))
                .findFirst();
    }

    public ScheduledTask createScheduledTask(String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        Instant now = Instant.now();
        ScheduledTask task = ScheduledTask.builder()
                .id(UUID.randomUUID().toString())
                .title(requireTitle(title))
                .description(normalizeOptionalValue(description))
                .prompt(normalizeOptionalValue(prompt))
                .reflectionModelTier(normalizeOptionalValue(reflectionModelTier))
                .reflectionTierPriority(reflectionTierPriority)
                .createdAt(now)
                .updatedAt(now)
                .build();
        List<ScheduledTask> tasks = getScheduledTasks();
        tasks.add(task);
        saveScheduledTasks(tasks);
        return task;
    }

    public ScheduledTask updateScheduledTask(String scheduledTaskId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority) {
        ScheduledTask task = getScheduledTask(scheduledTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId));
        task.setTitle(requireTitle(title));
        task.setDescription(normalizeOptionalValue(description));
        task.setPrompt(normalizeOptionalValue(prompt));
        task.setReflectionModelTier(normalizeOptionalValue(reflectionModelTier));
        if (reflectionTierPriority != null) {
            task.setReflectionTierPriority(reflectionTierPriority);
        }
        task.setUpdatedAt(Instant.now());
        saveScheduledTasks(getScheduledTasks());
        return task;
    }

    public void deleteScheduledTask(String scheduledTaskId) {
        List<ScheduledTask> tasks = getScheduledTasks();
        boolean removed = tasks.removeIf(task -> scheduledTaskId.equals(task.getId()));
        if (!removed) {
            throw new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId);
        }
        saveScheduledTasks(tasks);
    }

    public ScheduledTask createFromLegacyGoal(Goal goal) {
        if (goal == null) {
            throw new IllegalArgumentException("goal is required");
        }
        Instant now = Instant.now();
        ScheduledTask task = ScheduledTask.builder()
                .id(UUID.randomUUID().toString())
                .title(requireTitle(goal.getTitle()))
                .description(normalizeOptionalValue(goal.getDescription()))
                .prompt(normalizeOptionalValue(goal.getPrompt()))
                .reflectionModelTier(normalizeOptionalValue(goal.getReflectionModelTier()))
                .reflectionTierPriority(goal.isReflectionTierPriority())
                .consecutiveFailureCount(goal.getConsecutiveFailureCount())
                .reflectionRequired(goal.isReflectionRequired())
                .lastFailureSummary(goal.getLastFailureSummary())
                .lastFailureFingerprint(goal.getLastFailureFingerprint())
                .reflectionStrategy(goal.getReflectionStrategy())
                .lastUsedSkillName(goal.getLastUsedSkillName())
                .legacySourceType("GOAL")
                .legacySourceId(goal.getId())
                .lastFailureAt(goal.getLastFailureAt())
                .lastReflectionAt(goal.getLastReflectionAt())
                .createdAt(goal.getCreatedAt() != null ? goal.getCreatedAt() : now)
                .updatedAt(goal.getUpdatedAt() != null ? goal.getUpdatedAt() : now)
                .build();
        List<ScheduledTask> tasks = getScheduledTasks();
        tasks.add(task);
        saveScheduledTasks(tasks);
        return task;
    }

    public ScheduledTask createFromLegacyTask(Goal goal, AutoTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        Instant now = Instant.now();
        ScheduledTask scheduledTask = ScheduledTask.builder()
                .id(UUID.randomUUID().toString())
                .title(requireTitle(task.getTitle()))
                .description(resolveLegacyTaskDescription(goal, task))
                .prompt(normalizeOptionalValue(task.getPrompt()))
                .reflectionModelTier(normalizeOptionalValue(task.getReflectionModelTier()))
                .reflectionTierPriority(task.isReflectionTierPriority())
                .consecutiveFailureCount(task.getConsecutiveFailureCount())
                .reflectionRequired(task.isReflectionRequired())
                .lastFailureSummary(task.getLastFailureSummary())
                .lastFailureFingerprint(task.getLastFailureFingerprint())
                .reflectionStrategy(task.getReflectionStrategy())
                .lastUsedSkillName(task.getLastUsedSkillName())
                .legacySourceType("TASK")
                .legacySourceId(task.getId())
                .lastFailureAt(task.getLastFailureAt())
                .lastReflectionAt(task.getLastReflectionAt())
                .createdAt(task.getCreatedAt() != null ? task.getCreatedAt() : now)
                .updatedAt(task.getUpdatedAt() != null ? task.getUpdatedAt() : now)
                .build();
        List<ScheduledTask> tasks = getScheduledTasks();
        tasks.add(scheduledTask);
        saveScheduledTasks(tasks);
        return scheduledTask;
    }

    public void recordFailure(String scheduledTaskId, String failureSummary, String failureFingerprint,
            String activeSkillName, int threshold) {
        ScheduledTask task = getScheduledTask(scheduledTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId));
        Instant now = Instant.now();
        task.setConsecutiveFailureCount(task.getConsecutiveFailureCount() + 1);
        task.setReflectionRequired(task.getConsecutiveFailureCount() >= Math.max(1, threshold));
        task.setLastFailureSummary(normalizeOptionalValue(failureSummary));
        task.setLastFailureFingerprint(resolveFailureFingerprint(failureFingerprint, failureSummary));
        task.setLastFailureAt(now);
        task.setLastUsedSkillName(normalizeOptionalValue(activeSkillName));
        task.setUpdatedAt(now);
        saveScheduledTasks(getScheduledTasks());
    }

    public void recordSuccess(String scheduledTaskId, String activeSkillName) {
        ScheduledTask task = getScheduledTask(scheduledTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId));
        Instant now = Instant.now();
        task.setConsecutiveFailureCount(0);
        task.setReflectionRequired(false);
        task.setLastFailureSummary(null);
        task.setLastFailureFingerprint(null);
        task.setLastFailureAt(null);
        task.setLastUsedSkillName(normalizeOptionalValue(activeSkillName));
        task.setUpdatedAt(now);
        saveScheduledTasks(getScheduledTasks());
    }

    public void applyReflectionResult(String scheduledTaskId, String reflectionStrategy) {
        ScheduledTask task = getScheduledTask(scheduledTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId));
        Instant now = Instant.now();
        task.setReflectionStrategy(normalizeOptionalValue(reflectionStrategy));
        task.setReflectionRequired(false);
        task.setConsecutiveFailureCount(0);
        task.setLastReflectionAt(now);
        task.setUpdatedAt(now);
        saveScheduledTasks(getScheduledTasks());
    }

    public List<ScheduledTask> loadScheduledTasks() {
        try {
            String json = storagePort.getText(AUTO_DIR, FILE_NAME).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<ScheduledTask> tasks = objectMapper.readValue(json, TYPE_REF);
            return tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) { // NOSONAR
            log.debug("[ScheduledTask] Failed to load scheduled tasks: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveScheduledTasks(List<ScheduledTask> tasks) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tasks);
            storagePort.putTextAtomic(AUTO_DIR, FILE_NAME, json, true).join();
            cache = tasks;
        } catch (IOException | RuntimeException exception) { // NOSONAR
            throw new IllegalStateException("Failed to save scheduled tasks", exception);
        }
    }

    private String resolveLegacyTaskDescription(Goal goal, AutoTask task) {
        String description = normalizeOptionalValue(task.getDescription());
        if (description != null) {
            return description;
        }
        if (goal == null || StringValueSupport.isBlank(goal.getTitle())) {
            return null;
        }
        return "Migrated from goal: " + goal.getTitle();
    }

    private String resolveFailureFingerprint(String explicitFingerprint, String failureSummary) {
        String normalized = normalizeOptionalValue(explicitFingerprint);
        if (normalized != null) {
            return normalized;
        }
        return normalizeOptionalValue(failureSummary);
    }

    private String requireTitle(String title) {
        if (StringValueSupport.isBlank(title)) {
            throw new IllegalArgumentException("title is required");
        }
        return title.trim();
    }

    private String normalizeOptionalValue(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }
}
