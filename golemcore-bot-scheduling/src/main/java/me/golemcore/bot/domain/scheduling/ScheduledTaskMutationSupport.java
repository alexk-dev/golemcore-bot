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

import me.golemcore.bot.domain.support.StringValueSupport;
import java.time.Instant;
import java.util.UUID;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduledTask;

final class ScheduledTaskMutationSupport {

    private ScheduledTaskMutationSupport() {
    }

    static ScheduledTask createTask(String title, String description, String prompt, String reflectionModelTier,
            boolean reflectionTierPriority, ScheduledTask.ExecutionMode executionMode, String shellCommand,
            String shellWorkingDirectory) {
        Instant now = Instant.now();
        ScheduledTask.ExecutionMode normalizedExecutionMode = normalizeExecutionMode(executionMode);
        return ScheduledTask.builder().id(UUID.randomUUID().toString()).title(requireTitle(title))
                .description(normalizeOptionalValue(description)).prompt(normalizeOptionalValue(prompt))
                .executionMode(normalizedExecutionMode)
                .shellCommand(normalizeShellCommand(normalizedExecutionMode, shellCommand))
                .shellWorkingDirectory(normalizeShellWorkingDirectory(normalizedExecutionMode, shellWorkingDirectory))
                .reflectionModelTier(normalizeOptionalValue(reflectionModelTier))
                .reflectionTierPriority(reflectionTierPriority).createdAt(now).updatedAt(now).build();
    }

    static void applyUpdate(ScheduledTask task, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, ScheduledTask.ExecutionMode executionMode,
            String shellCommand, String shellWorkingDirectory) {
        ScheduledTask.ExecutionMode normalizedExecutionMode = executionMode != null
                ? normalizeExecutionMode(executionMode)
                : task.getExecutionModeOrDefault();
        task.setTitle(requireTitle(title));
        task.setDescription(normalizeOptionalValue(description));
        task.setPrompt(normalizeOptionalValue(prompt));
        task.setExecutionMode(normalizedExecutionMode);
        task.setShellCommand(resolveUpdatedShellCommand(task, normalizedExecutionMode, shellCommand));
        task.setShellWorkingDirectory(
                resolveUpdatedShellWorkingDirectory(task, normalizedExecutionMode, shellWorkingDirectory));
        task.setReflectionModelTier(normalizeOptionalValue(reflectionModelTier));
        if (reflectionTierPriority != null) {
            task.setReflectionTierPriority(reflectionTierPriority);
        }
        task.setUpdatedAt(Instant.now());
    }

    static ScheduledTask createFromLegacyGoal(Goal goal) {
        Instant now = Instant.now();
        return ScheduledTask.builder().id(UUID.randomUUID().toString()).title(requireTitle(goal.getTitle()))
                .description(normalizeOptionalValue(goal.getDescription()))
                .prompt(normalizeOptionalValue(goal.getPrompt()))
                .reflectionModelTier(normalizeOptionalValue(goal.getReflectionModelTier()))
                .reflectionTierPriority(goal.isReflectionTierPriority())
                .consecutiveFailureCount(goal.getConsecutiveFailureCount())
                .reflectionRequired(goal.isReflectionRequired()).lastFailureSummary(goal.getLastFailureSummary())
                .lastFailureFingerprint(goal.getLastFailureFingerprint())
                .reflectionStrategy(goal.getReflectionStrategy()).lastUsedSkillName(goal.getLastUsedSkillName())
                .legacySourceType("GOAL").legacySourceId(goal.getId()).lastFailureAt(goal.getLastFailureAt())
                .lastReflectionAt(goal.getLastReflectionAt())
                .createdAt(goal.getCreatedAt() != null ? goal.getCreatedAt() : now)
                .updatedAt(goal.getUpdatedAt() != null ? goal.getUpdatedAt() : now).build();
    }

    static ScheduledTask createFromLegacyTask(Goal goal, AutoTask task) {
        Instant now = Instant.now();
        return ScheduledTask.builder().id(UUID.randomUUID().toString()).title(requireTitle(task.getTitle()))
                .description(resolveLegacyTaskDescription(goal, task)).prompt(normalizeOptionalValue(task.getPrompt()))
                .reflectionModelTier(normalizeOptionalValue(task.getReflectionModelTier()))
                .reflectionTierPriority(task.isReflectionTierPriority())
                .consecutiveFailureCount(task.getConsecutiveFailureCount())
                .reflectionRequired(task.isReflectionRequired()).lastFailureSummary(task.getLastFailureSummary())
                .lastFailureFingerprint(task.getLastFailureFingerprint())
                .reflectionStrategy(task.getReflectionStrategy()).lastUsedSkillName(task.getLastUsedSkillName())
                .legacySourceType("TASK").legacySourceId(task.getId()).lastFailureAt(task.getLastFailureAt())
                .lastReflectionAt(task.getLastReflectionAt())
                .createdAt(task.getCreatedAt() != null ? task.getCreatedAt() : now)
                .updatedAt(task.getUpdatedAt() != null ? task.getUpdatedAt() : now).build();
    }

    static ScheduledTask normalizeLoadedTask(ScheduledTask task) {
        if (task == null) {
            return null;
        }
        ScheduledTask.ExecutionMode executionMode = normalizeExecutionMode(task.getExecutionMode());
        task.setExecutionMode(executionMode);
        task.setShellCommand(normalizeOptionalValue(task.getShellCommand()));
        task.setShellWorkingDirectory(normalizeOptionalValue(task.getShellWorkingDirectory()));
        if (executionMode != ScheduledTask.ExecutionMode.SHELL_COMMAND) {
            task.setShellCommand(null);
            task.setShellWorkingDirectory(null);
        }
        return task;
    }

    static boolean shouldRequireReflection(ScheduledTask task, int threshold) {
        return task.getExecutionModeOrDefault() == ScheduledTask.ExecutionMode.AGENT_PROMPT
                && task.getConsecutiveFailureCount() >= Math.max(1, threshold);
    }

    static String resolveFailureFingerprint(String explicitFingerprint, String failureSummary) {
        String normalized = normalizeOptionalValue(explicitFingerprint);
        if (normalized != null) {
            return normalized;
        }
        return normalizeOptionalValue(failureSummary);
    }

    static String normalizeOptionalValue(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private static String requireTitle(String title) {
        if (StringValueSupport.isBlank(title)) {
            throw new IllegalArgumentException("title is required");
        }
        return title.trim();
    }

    private static ScheduledTask.ExecutionMode normalizeExecutionMode(ScheduledTask.ExecutionMode executionMode) {
        return executionMode != null ? executionMode : ScheduledTask.ExecutionMode.AGENT_PROMPT;
    }

    private static String normalizeShellCommand(ScheduledTask.ExecutionMode executionMode, String shellCommand) {
        if (executionMode != ScheduledTask.ExecutionMode.SHELL_COMMAND) {
            return null;
        }
        String normalized = normalizeOptionalValue(shellCommand);
        if (normalized == null) {
            throw new IllegalArgumentException("shellCommand is required for shell scheduled tasks");
        }
        return normalized;
    }

    private static String normalizeShellWorkingDirectory(ScheduledTask.ExecutionMode executionMode,
            String shellWorkingDirectory) {
        if (executionMode != ScheduledTask.ExecutionMode.SHELL_COMMAND) {
            return null;
        }
        return normalizeOptionalValue(shellWorkingDirectory);
    }

    private static String resolveUpdatedShellCommand(ScheduledTask existingTask,
            ScheduledTask.ExecutionMode executionMode, String shellCommand) {
        if (executionMode != ScheduledTask.ExecutionMode.SHELL_COMMAND) {
            return null;
        }
        String requested = shellCommand != null ? shellCommand : existingTask.getShellCommand();
        return normalizeShellCommand(executionMode, requested);
    }

    private static String resolveUpdatedShellWorkingDirectory(ScheduledTask existingTask,
            ScheduledTask.ExecutionMode executionMode, String shellWorkingDirectory) {
        if (executionMode != ScheduledTask.ExecutionMode.SHELL_COMMAND) {
            return null;
        }
        String requested = shellWorkingDirectory != null ? shellWorkingDirectory
                : existingTask.getShellWorkingDirectory();
        return normalizeShellWorkingDirectory(executionMode, requested);
    }

    private static String resolveLegacyTaskDescription(Goal goal, AutoTask task) {
        String description = normalizeOptionalValue(task.getDescription());
        if (description != null) {
            return description;
        }
        if (goal == null || StringValueSupport.isBlank(goal.getTitle())) {
            return null;
        }
        return "Migrated from goal: " + goal.getTitle();
    }
}
