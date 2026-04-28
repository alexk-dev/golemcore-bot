package me.golemcore.bot.domain.auto;

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

import me.golemcore.bot.domain.session.SessionScopedGoalService;
import me.golemcore.bot.domain.session.SessionDiaryService;
import me.golemcore.bot.domain.scheduling.PersistentScheduledTaskService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.port.outbound.SessionGoalCleanupPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

/**
 * Auto mode facade that routes transient goals/tasks to the current session and
 * keeps recurring work in dedicated scheduled tasks.
 */
@Service
@Slf4j
public class AutoModeService implements SessionGoalCleanupPort {

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final RuntimeConfigService runtimeConfigService;
    private final SessionScopedGoalService sessionScopedGoalService;
    private final SessionDiaryService sessionDiaryService;
    private final PersistentScheduledTaskService persistentScheduledTaskService;

    private volatile boolean enabled = false;

    public AutoModeService(StoragePort storagePort, ObjectMapper objectMapper,
            RuntimeConfigService runtimeConfigService,
            SessionScopedGoalService sessionScopedGoalService,
            SessionDiaryService sessionDiaryService,
            PersistentScheduledTaskService persistentScheduledTaskService) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
        this.runtimeConfigService = runtimeConfigService;
        this.sessionScopedGoalService = sessionScopedGoalService;
        this.sessionDiaryService = sessionDiaryService;
        this.persistentScheduledTaskService = persistentScheduledTaskService;
    }

    public boolean isFeatureEnabled() {
        return runtimeConfigService.isAutoModeEnabled();
    }

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

    public Goal createGoal(String title, String description) {
        return createGoal(title, description, null, null, false);
    }

    public Goal createGoal(String title, String description, String prompt) {
        return createGoal(title, description, prompt, null, false);
    }

    public Goal createGoal(String sessionId, String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        return sessionScopedGoalService.createGoal(sessionId, title, description, prompt, reflectionModelTier,
                reflectionTierPriority);
    }

    public Goal createGoal(String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        return sessionScopedGoalService.createGoal(
                requireCurrentSessionId(),
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority);
    }

    public List<Goal> getGoals() {
        return sessionScopedGoalService.getGoals(requireCurrentSessionId());
    }

    public List<Goal> getGoals(String sessionId) {
        return sessionScopedGoalService.getGoals(sessionId);
    }

    public List<Goal> getActiveGoals() {
        return sessionScopedGoalService.getActiveGoals(requireCurrentSessionId());
    }

    public List<Goal> getActiveGoals(String sessionId) {
        return sessionScopedGoalService.getActiveGoals(sessionId);
    }

    public Optional<Goal> getGoal(String goalId) {
        return sessionScopedGoalService.getGoal(requireCurrentSessionId(), goalId);
    }

    public Optional<Goal> getGoal(String sessionId, String goalId) {
        return sessionScopedGoalService.getGoal(sessionId, goalId);
    }

    public Goal updateGoal(String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, Goal.GoalStatus status) {
        return sessionScopedGoalService.updateGoal(
                requireCurrentSessionId(),
                goalId,
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                status);
    }

    public AutoTask addTask(String goalId, String title, String description, int order) {
        return sessionScopedGoalService.addTask(requireCurrentSessionId(), goalId, title, description, null, null,
                false, order);
    }

    public AutoTask addTask(String goalId, String title, String description, String prompt, int order) {
        return sessionScopedGoalService.addTask(requireCurrentSessionId(), goalId, title, description, prompt, null,
                false, order);
    }

    public AutoTask addTask(String goalId, String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority, int order) {
        return sessionScopedGoalService.addTask(requireCurrentSessionId(), goalId, title, description, prompt,
                reflectionModelTier, reflectionTierPriority, order);
    }

    public AutoTask createTask(String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, AutoTask.TaskStatus status) {
        return sessionScopedGoalService.createTask(requireCurrentSessionId(), goalId, title, description, prompt,
                reflectionModelTier, reflectionTierPriority, status);
    }

    public AutoTask addStandaloneTask(String title, String description) {
        return addStandaloneTask(title, description, null, null, false);
    }

    public AutoTask addStandaloneTask(String title, String description, String prompt) {
        return addStandaloneTask(title, description, prompt, null, false);
    }

    public AutoTask addStandaloneTask(String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        return sessionScopedGoalService.createTask(requireCurrentSessionId(), null, title, description, prompt,
                reflectionModelTier, reflectionTierPriority, AutoTask.TaskStatus.PENDING);
    }

    public boolean isInboxGoal(Goal goal) {
        return sessionScopedGoalService.isInboxGoal(goal);
    }

    public String getInboxGoalId() {
        return "inbox";
    }

    public Goal getOrCreateInboxGoal() {
        return sessionScopedGoalService.getOrCreateInboxGoal(requireCurrentSessionId());
    }

    public void updateTaskStatus(String goalId, String taskId,
            AutoTask.TaskStatus status, String result) {
        sessionScopedGoalService.updateTaskStatus(requireCurrentSessionId(), goalId, taskId, status, result);
    }

    public Optional<AutoTask> getTask(String taskId) {
        return sessionScopedGoalService.getTask(requireCurrentSessionId(), taskId);
    }

    public AutoTask updateTask(
            String taskId,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority,
            AutoTask.TaskStatus status) {
        return sessionScopedGoalService.updateTask(requireCurrentSessionId(), taskId, title, description, prompt,
                reflectionModelTier, reflectionTierPriority, status);
    }

    public Optional<AutoTask> getNextPendingTask(String sessionId) {
        return sessionScopedGoalService.getNextPendingTask(sessionId);
    }

    public Optional<AutoTask> getNextPendingTask() {
        return sessionScopedGoalService.getNextPendingTask(requireCurrentSessionId());
    }

    public void completeGoal(String goalId) {
        sessionScopedGoalService.completeGoal(requireCurrentSessionId(), goalId);
    }

    public void deleteGoal(String goalId) {
        sessionScopedGoalService.deleteGoal(requireCurrentSessionId(), goalId);
    }

    public void deleteTask(String goalId, String taskId) {
        sessionScopedGoalService.deleteTask(requireCurrentSessionId(), goalId, taskId);
    }

    public void deleteTask(String taskId) {
        sessionScopedGoalService.deleteTask(requireCurrentSessionId(), taskId);
    }

    public int clearCompletedGoals() {
        return sessionScopedGoalService.clearCompletedGoals(requireCurrentSessionId());
    }

    public void recordAutoRunFailure(String goalId, String taskId, String failureSummary, String failureFingerprint,
            String activeSkillName) {
        sessionScopedGoalService.recordAutoRunFailure(requireCurrentSessionId(), goalId, taskId, failureSummary,
                failureFingerprint, activeSkillName);
    }

    public void recordAutoRunFailure(String sessionId, String goalId, String taskId, String failureSummary,
            String failureFingerprint, String activeSkillName) {
        sessionScopedGoalService.recordAutoRunFailure(sessionId, goalId, taskId, failureSummary, failureFingerprint,
                activeSkillName);
    }

    public void recordAutoRunSuccess(String goalId, String taskId, String activeSkillName) {
        sessionScopedGoalService.recordAutoRunSuccess(requireCurrentSessionId(), goalId, taskId, activeSkillName);
    }

    public void recordAutoRunSuccess(String sessionId, String goalId, String taskId, String activeSkillName) {
        sessionScopedGoalService.recordAutoRunSuccess(sessionId, goalId, taskId, activeSkillName);
    }

    public boolean shouldTriggerReflection(String goalId, String taskId) {
        return sessionScopedGoalService.shouldTriggerReflection(requireCurrentSessionId(), goalId, taskId);
    }

    public boolean shouldTriggerReflection(String sessionId, String goalId, String taskId) {
        return sessionScopedGoalService.shouldTriggerReflection(sessionId, goalId, taskId);
    }

    public void applyReflectionResult(String goalId, String taskId, String reflectionStrategy) {
        sessionScopedGoalService.applyReflectionResult(requireCurrentSessionId(), goalId, taskId, reflectionStrategy);
    }

    public void applyReflectionResult(String sessionId, String goalId, String taskId, String reflectionStrategy) {
        sessionScopedGoalService.applyReflectionResult(sessionId, goalId, taskId, reflectionStrategy);
    }

    public TaskReflectionState resolveTaskReflectionState(String goalId, String taskId) {
        return toTaskReflectionState(sessionScopedGoalService.resolveTaskReflectionState(requireCurrentSessionId(),
                goalId, taskId));
    }

    public TaskReflectionState resolveTaskReflectionState(String sessionId, String goalId, String taskId) {
        return toTaskReflectionState(sessionScopedGoalService.resolveTaskReflectionState(sessionId, goalId, taskId));
    }

    public String resolveReflectionTier(String goalId, String taskId, Skill skill) {
        return resolveReflectionTier(requireCurrentSessionId(), goalId, taskId, skill);
    }

    public String resolveReflectionTier(String sessionId, String goalId, String taskId, Skill skill) {
        TaskReflectionState state = resolveTaskReflectionState(sessionId, goalId, taskId);
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
        return isReflectionTierPriority(requireCurrentSessionId(), goalId, taskId);
    }

    public boolean isReflectionTierPriority(String sessionId, String goalId, String taskId) {
        TaskReflectionState state = resolveTaskReflectionState(sessionId, goalId, taskId);
        if (state.taskReflectionModelTier() != null && !state.taskReflectionModelTier().isBlank()) {
            return state.taskReflectionTierPriority();
        }
        if (state.goalReflectionModelTier() != null && !state.goalReflectionModelTier().isBlank()) {
            return state.goalReflectionTierPriority();
        }
        return runtimeConfigService.isAutoReflectionTierPriority();
    }

    public int getReflectionFailureThreshold() {
        return sessionScopedGoalService.getReflectionFailureThreshold();
    }

    public void writeDiary(DiaryEntry entry) {
        sessionDiaryService.writeDiary(requireCurrentSessionId(), entry);
    }

    public List<DiaryEntry> getRecentDiary(String sessionId, int count) {
        return sessionDiaryService.getRecentDiary(sessionId, count);
    }

    public List<DiaryEntry> getRecentDiary(int count) {
        return sessionDiaryService.getRecentDiary(requireCurrentSessionId(), count);
    }

    public String buildAutoContext() {
        return sessionScopedGoalService.buildAutoContext(requireCurrentSessionId());
    }

    public String buildAutoContext(String sessionId) {
        return sessionScopedGoalService.buildAutoContext(sessionId);
    }

    public String buildAutoContext(String requestedGoalId, String requestedTaskId) {
        return sessionScopedGoalService.buildAutoContext(requireCurrentSessionId(), requestedGoalId, requestedTaskId);
    }

    public String buildAutoContext(String sessionId, String requestedGoalId, String requestedTaskId) {
        return sessionScopedGoalService.buildAutoContext(sessionId, requestedGoalId, requestedTaskId);
    }

    public void loadState() {
        try {
            String json = storagePort.getText("auto", "state.json").join();
            if (json != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> state = objectMapper.readValue(json, Map.class);
                enabled = Boolean.TRUE.equals(state.get("enabled"));
            }
            log.info("[AutoMode] Loaded state: enabled={}", enabled);
        } catch (IOException | RuntimeException exception) {
            log.debug("[AutoMode] Failed to load state: {}", exception.getMessage());
        }
    }

    public Optional<Goal> findGoalForTask(String taskId) {
        return sessionScopedGoalService.findGoalForTask(requireCurrentSessionId(), taskId);
    }

    public Optional<Goal> findGoalForTask(String sessionId, String taskId) {
        return sessionScopedGoalService.findGoalForTask(sessionId, taskId);
    }

    @Override
    public void deleteSessionGoals(String sessionId) {
        sessionScopedGoalService.replaceGoals(sessionId, List.of());
    }

    public List<ScheduledTask> getScheduledTasks() {
        return persistentScheduledTaskService.getScheduledTasks();
    }

    public Optional<ScheduledTask> getScheduledTask(String scheduledTaskId) {
        return persistentScheduledTaskService.getScheduledTask(scheduledTaskId);
    }

    public ScheduledTask createScheduledTask(String title, String description, String prompt,
            String reflectionModelTier, boolean reflectionTierPriority) {
        return persistentScheduledTaskService.createScheduledTask(title, description, prompt, reflectionModelTier,
                reflectionTierPriority);
    }

    public ScheduledTask createScheduledTask(
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            boolean reflectionTierPriority,
            ScheduledTask.ExecutionMode executionMode,
            String shellCommand,
            String shellWorkingDirectory) {
        return persistentScheduledTaskService.createScheduledTask(
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                executionMode,
                shellCommand,
                shellWorkingDirectory);
    }

    public ScheduledTask updateScheduledTask(String scheduledTaskId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority) {
        return persistentScheduledTaskService.updateScheduledTask(scheduledTaskId, title, description, prompt,
                reflectionModelTier, reflectionTierPriority);
    }

    public ScheduledTask updateScheduledTask(
            String scheduledTaskId,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority,
            ScheduledTask.ExecutionMode executionMode,
            String shellCommand,
            String shellWorkingDirectory) {
        return persistentScheduledTaskService.updateScheduledTask(
                scheduledTaskId,
                title,
                description,
                prompt,
                reflectionModelTier,
                reflectionTierPriority,
                executionMode,
                shellCommand,
                shellWorkingDirectory);
    }

    public void deleteScheduledTask(String scheduledTaskId) {
        persistentScheduledTaskService.deleteScheduledTask(scheduledTaskId);
    }

    public void recordScheduledTaskFailure(String scheduledTaskId, String failureSummary, String failureFingerprint,
            String activeSkillName) {
        persistentScheduledTaskService.recordFailure(scheduledTaskId, failureSummary, failureFingerprint,
                activeSkillName, getReflectionFailureThreshold());
    }

    public void recordScheduledTaskSuccess(String scheduledTaskId, String activeSkillName) {
        persistentScheduledTaskService.recordSuccess(scheduledTaskId, activeSkillName);
    }

    public void applyScheduledTaskReflectionResult(String scheduledTaskId, String reflectionStrategy) {
        persistentScheduledTaskService.applyReflectionResult(scheduledTaskId, reflectionStrategy);
    }

    public boolean shouldTriggerScheduledTaskReflection(String scheduledTaskId) {
        return persistentScheduledTaskService.getScheduledTask(scheduledTaskId)
                .map(ScheduledTask::isReflectionRequired)
                .orElse(false);
    }

    public TaskReflectionState resolveScheduledTaskReflectionState(String scheduledTaskId) {
        ScheduledTask task = persistentScheduledTaskService.getScheduledTask(scheduledTaskId)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled task not found: " + scheduledTaskId));
        return new TaskReflectionState(
                null,
                false,
                task.getReflectionModelTier(),
                task.isReflectionTierPriority(),
                task.getLastUsedSkillName(),
                task.getConsecutiveFailureCount(),
                task.isReflectionRequired(),
                task.getLastFailureSummary(),
                task.getLastFailureFingerprint(),
                task.getReflectionStrategy());
    }

    public String resolveScheduledTaskReflectionTier(String scheduledTaskId, Skill skill) {
        TaskReflectionState state = resolveScheduledTaskReflectionState(scheduledTaskId);
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
        return runtimeConfigService.getAutoReflectionModelTier();
    }

    public boolean isScheduledTaskReflectionTierPriority(String scheduledTaskId) {
        TaskReflectionState state = resolveScheduledTaskReflectionState(scheduledTaskId);
        if (state.taskReflectionModelTier() != null && !state.taskReflectionModelTier().isBlank()) {
            return state.taskReflectionTierPriority();
        }
        return runtimeConfigService.isAutoReflectionTierPriority();
    }

    private TaskReflectionState toTaskReflectionState(SessionScopedGoalService.TaskReflectionStateSnapshot snapshot) {
        return new TaskReflectionState(
                snapshot.goalReflectionModelTier(),
                snapshot.goalReflectionTierPriority(),
                snapshot.taskReflectionModelTier(),
                snapshot.taskReflectionTierPriority(),
                snapshot.lastUsedSkillName(),
                snapshot.consecutiveFailureCount(),
                snapshot.reflectionRequired(),
                snapshot.lastFailureSummary(),
                snapshot.lastFailureFingerprint(),
                snapshot.reflectionStrategy());
    }

    private void saveState(boolean enabledValue) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("enabled", enabledValue));
            storagePort.putText("auto", "state.json", json).join();
        } catch (Exception exception) {
            log.error("[AutoMode] Failed to save state", exception);
        }
    }

    private String requireCurrentSessionId() {
        AgentContext context = AgentContextHolder.get();
        if (context == null || context.getSession() == null || context.getSession().getId() == null) {
            throw new IllegalStateException("No current session available for auto mode operation");
        }
        return context.getSession().getId();
    }

    public SessionScopedGoalService getSessionScopedGoalService() {
        return sessionScopedGoalService;
    }

    public PersistentScheduledTaskService getPersistentScheduledTaskService() {
        return persistentScheduledTaskService;
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
}
