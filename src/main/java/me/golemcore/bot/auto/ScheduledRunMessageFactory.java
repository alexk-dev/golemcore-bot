package me.golemcore.bot.auto;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.service.TraceContextSupport;
import me.golemcore.bot.domain.service.TraceNamingSupport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds scheduled run prompts, reflection prompts, and synthetic messages.
 */
@Component
@Slf4j
public class ScheduledRunMessageFactory {

    private static final String REFLECTION_PROMPT_SUFFIX = "' and propose an alternative strategy for the next run.";

    private final AutoModeService autoModeService;
    private final RuntimeConfigService runtimeConfigService;
    private final SkillComponent skillComponent;

    public ScheduledRunMessageFactory(
            AutoModeService autoModeService,
            RuntimeConfigService runtimeConfigService,
            SkillComponent skillComponent) {
        this.autoModeService = autoModeService;
        this.runtimeConfigService = runtimeConfigService;
        this.skillComponent = skillComponent;
    }

    public Optional<ScheduledRunMessage> buildForSchedule(ScheduleEntry schedule) {
        if (schedule == null || schedule.getType() == null) {
            return Optional.empty();
        }
        try {
            return switch (schedule.getType()) {
            case SCHEDULED_TASK -> buildScheduledTaskMessage(schedule.getTargetId());
            case GOAL -> buildGoalMessage(schedule.getTargetId());
            case TASK -> buildTaskMessage(schedule.getTargetId());
            };
        } catch (IllegalStateException exception) {
            log.warn("[ScheduledRunMessageFactory] Cannot build {} schedule {} without session context: {}",
                    schedule.getType(), schedule.getId(), exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ScheduledRunMessage> buildReflectionMessage(ScheduledRunMessage source, String scheduleId) {
        if (source == null) {
            return Optional.empty();
        }
        if (!StringValueSupport.isBlank(source.scheduledTaskId())) {
            return buildScheduledTaskReflectionMessage(source, scheduleId);
        }
        if (StringValueSupport.isBlank(source.goalId())) {
            return Optional.empty();
        }

        AutoModeService.TaskReflectionState reflectionState = autoModeService.resolveTaskReflectionState(
                source.goalId(), source.taskId());
        Skill reflectedSkill = resolveReflectedSkill(reflectionState.lastUsedSkillName());
        String tier = autoModeService.resolveReflectionTier(source.goalId(), source.taskId(), reflectedSkill);
        boolean priority = autoModeService.isReflectionTierPriority(source.goalId(), source.taskId());

        StringBuilder prompt = new StringBuilder();
        if (!StringValueSupport.isBlank(source.taskId())) {
            prompt.append("[AUTO][REFLECTION] Analyze the repeated failure for task '")
                    .append(source.taskTitle() != null ? source.taskTitle() : source.taskId())
                    .append(REFLECTION_PROMPT_SUFFIX);
        } else {
            prompt.append("[AUTO][REFLECTION] Analyze the repeated failure while planning goal '")
                    .append(source.goalTitle() != null ? source.goalTitle() : source.goalId())
                    .append(REFLECTION_PROMPT_SUFFIX);
        }
        if (!StringValueSupport.isBlank(reflectionState.lastFailureSummary())) {
            prompt.append(" Latest failure: ").append(reflectionState.lastFailureSummary()).append('.');
        }
        if (!StringValueSupport.isBlank(reflectionState.lastFailureFingerprint())) {
            prompt.append(" Failure fingerprint: ").append(reflectionState.lastFailureFingerprint()).append('.');
        }
        prompt.append(" Schedule: ").append(scheduleId).append('.');
        prompt.append(" Return a concise recovery strategy that changes the next approach.");

        return Optional.of(ScheduledRunMessage.forReflection(
                source,
                prompt.toString(),
                tier,
                priority));
    }

    public Message buildSyntheticMessage(
            ScheduledRunMessage scheduledRunMessage,
            ScheduleEntry schedule,
            ScheduleDeliveryContext deliveryContext,
            String runId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.AUTO_MODE, true);
        metadata.put(ContextAttributes.AUTO_RUN_KIND, scheduledRunMessage.runKind().name());
        metadata.put(ContextAttributes.AUTO_RUN_ID, runId);
        metadata.put(ContextAttributes.AUTO_SCHEDULE_ID, schedule.getId());
        metadata.put(ContextAttributes.CONVERSATION_KEY, deliveryContext.sessionChatId());
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, deliveryContext.transportChatId());
        if (!StringValueSupport.isBlank(scheduledRunMessage.goalId())) {
            metadata.put(ContextAttributes.AUTO_GOAL_ID, scheduledRunMessage.goalId());
        }
        if (!StringValueSupport.isBlank(scheduledRunMessage.taskId())) {
            metadata.put(ContextAttributes.AUTO_TASK_ID, scheduledRunMessage.taskId());
        }
        if (!StringValueSupport.isBlank(scheduledRunMessage.scheduledTaskId())) {
            metadata.put(ContextAttributes.AUTO_SCHEDULED_TASK_ID, scheduledRunMessage.scheduledTaskId());
        }
        if (scheduledRunMessage.reflectionActive()) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }
        if (!StringValueSupport.isBlank(scheduledRunMessage.reflectionTier())) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_TIER, scheduledRunMessage.reflectionTier());
        }
        metadata.put(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, scheduledRunMessage.reflectionTierPriority());
        metadata = TraceContextSupport.ensureRootMetadata(
                metadata,
                TraceSpanKind.INTERNAL,
                TraceNamingSupport.autoSchedule(schedule));

        return Message.builder()
                .role("user")
                .content(scheduledRunMessage.content())
                .channelType(deliveryContext.channelType())
                .chatId(deliveryContext.sessionChatId())
                .senderId("auto")
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    public String buildReportHeader(ScheduledRunMessage scheduledRunMessage) {
        StringBuilder header = new StringBuilder("\uD83D\uDCCB ");
        if (!StringValueSupport.isBlank(scheduledRunMessage.goalTitle())) {
            header.append("Goal: ").append(scheduledRunMessage.goalTitle());
            if (!StringValueSupport.isBlank(scheduledRunMessage.taskTitle())) {
                header.append(" / Task: ").append(scheduledRunMessage.taskTitle());
            }
        } else if (!StringValueSupport.isBlank(scheduledRunMessage.scheduledTaskId())
                && !StringValueSupport.isBlank(scheduledRunMessage.taskTitle())) {
            header.append("Scheduled task: ").append(scheduledRunMessage.taskTitle());
        } else if (!StringValueSupport.isBlank(scheduledRunMessage.taskTitle())) {
            header.append("Task: ").append(scheduledRunMessage.taskTitle());
        } else {
            header.append("Scheduled run report");
        }
        return header.toString();
    }

    private Optional<ScheduledRunMessage> buildGoalMessage(String goalId) {
        Optional<Goal> goalOpt = autoModeService.getGoal(goalId);
        if (goalOpt.isEmpty()) {
            log.warn("[ScheduledRunMessageFactory] Goal not found for schedule: {}", goalId);
            return Optional.empty();
        }

        Goal goal = goalOpt.get();
        if (goal.getStatus() != Goal.GoalStatus.ACTIVE) {
            log.debug("[ScheduledRunMessageFactory] Goal {} is not active ({}), skipping", goalId, goal.getStatus());
            return Optional.empty();
        }

        Optional<AutoTask> nextTask = goal.getTasks().stream()
                .filter(task -> task.getStatus() == AutoTask.TaskStatus.PENDING
                        || task.getStatus() == AutoTask.TaskStatus.IN_PROGRESS
                        || task.getStatus() == AutoTask.TaskStatus.FAILED)
                .min(Comparator.comparingInt(AutoTask::getOrder));

        if (nextTask.isPresent()) {
            AutoTask task = nextTask.get();
            return Optional.of(ScheduledRunMessage.forTask(
                    buildTaskPrompt("Continue working on task", task.getExecutionPrompt(), goal.getTitle(),
                            goalId, task.getId()),
                    goalId,
                    task.getId(),
                    goal.getTitle(),
                    task.getTitle(),
                    resolveReflectionTier(goal, task),
                    resolveReflectionTierPriority(goal, task),
                    false));
        }

        if (goal.getTasks().isEmpty()) {
            return Optional.of(ScheduledRunMessage.forGoal(
                    buildGoalPrompt("Plan tasks for goal", goal.getExecutionPrompt(), goalId),
                    goalId,
                    goal.getTitle(),
                    goal.getReflectionModelTier(),
                    goal.isReflectionTierPriority()));
        }

        log.debug("[ScheduledRunMessageFactory] All tasks for goal {} are done, skipping", goalId);
        return Optional.empty();
    }

    private Optional<ScheduledRunMessage> buildTaskMessage(String taskId) {
        Optional<Goal> goalOpt = autoModeService.findGoalForTask(taskId);
        if (goalOpt.isEmpty()) {
            log.warn("[ScheduledRunMessageFactory] Task not found: {}", taskId);
            return Optional.empty();
        }

        Goal goal = goalOpt.get();
        Optional<AutoTask> taskOpt = goal.getTasks().stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst();
        if (taskOpt.isEmpty()) {
            return Optional.empty();
        }

        AutoTask task = taskOpt.get();
        if (task.getStatus() == AutoTask.TaskStatus.SKIPPED) {
            log.debug("[ScheduledRunMessageFactory] Task {} is skipped, not running", taskId);
            return Optional.empty();
        }

        return Optional.of(ScheduledRunMessage.forTask(
                buildTaskPrompt("Work on task", task.getExecutionPrompt(), goal.getTitle(), goal.getId(), taskId),
                goal.getId(),
                taskId,
                goal.getTitle(),
                task.getTitle(),
                resolveReflectionTier(goal, task),
                resolveReflectionTierPriority(goal, task),
                task.getStatus() == AutoTask.TaskStatus.COMPLETED));
    }

    private Optional<ScheduledRunMessage> buildScheduledTaskMessage(String scheduledTaskId) {
        Optional<ScheduledTask> taskOpt = autoModeService.getScheduledTask(scheduledTaskId);
        if (taskOpt.isEmpty()) {
            log.warn("[ScheduledRunMessageFactory] Scheduled task not found for schedule: {}", scheduledTaskId);
            return Optional.empty();
        }

        ScheduledTask task = taskOpt.get();
        return Optional.of(ScheduledRunMessage.forScheduledTask(
                buildScheduledTaskPrompt(task.getExecutionPrompt(), task.getTitle(), scheduledTaskId),
                scheduledTaskId,
                task.getTitle(),
                resolveReflectionTier(task),
                resolveReflectionTierPriority(task)));
    }

    private Optional<ScheduledRunMessage> buildScheduledTaskReflectionMessage(
            ScheduledRunMessage source,
            String scheduleId) {
        AutoModeService.TaskReflectionState reflectionState = autoModeService.resolveScheduledTaskReflectionState(
                source.scheduledTaskId());
        Skill reflectedSkill = resolveReflectedSkill(reflectionState.lastUsedSkillName());
        String tier = autoModeService.resolveScheduledTaskReflectionTier(source.scheduledTaskId(), reflectedSkill);
        boolean priority = autoModeService.isScheduledTaskReflectionTierPriority(source.scheduledTaskId());

        StringBuilder prompt = new StringBuilder();
        prompt.append("[AUTO][REFLECTION] Analyze the repeated failure for scheduled task '")
                .append(source.taskTitle() != null ? source.taskTitle() : source.scheduledTaskId())
                .append(REFLECTION_PROMPT_SUFFIX);
        if (!StringValueSupport.isBlank(reflectionState.lastFailureSummary())) {
            prompt.append(" Latest failure: ").append(reflectionState.lastFailureSummary()).append('.');
        }
        if (!StringValueSupport.isBlank(reflectionState.lastFailureFingerprint())) {
            prompt.append(" Failure fingerprint: ").append(reflectionState.lastFailureFingerprint()).append('.');
        }
        prompt.append(" Schedule: ").append(scheduleId).append('.');
        prompt.append(" Return a concise recovery strategy that changes the next approach.");

        return Optional.of(ScheduledRunMessage.forReflection(
                source,
                prompt.toString(),
                tier,
                priority));
    }

    private String resolveReflectionTier(Goal goal, AutoTask task) {
        if (task != null && !StringValueSupport.isBlank(task.getReflectionModelTier())) {
            return task.getReflectionModelTier();
        }
        if (goal != null && !StringValueSupport.isBlank(goal.getReflectionModelTier())) {
            return goal.getReflectionModelTier();
        }
        return runtimeConfigService.getAutoReflectionModelTier();
    }

    private boolean resolveReflectionTierPriority(Goal goal, AutoTask task) {
        if (task != null && !StringValueSupport.isBlank(task.getReflectionModelTier())) {
            return task.isReflectionTierPriority();
        }
        if (goal != null && !StringValueSupport.isBlank(goal.getReflectionModelTier())) {
            return goal.isReflectionTierPriority();
        }
        return runtimeConfigService.isAutoReflectionTierPriority();
    }

    private String resolveReflectionTier(ScheduledTask task) {
        if (task != null && !StringValueSupport.isBlank(task.getReflectionModelTier())) {
            return task.getReflectionModelTier();
        }
        return runtimeConfigService.getAutoReflectionModelTier();
    }

    private boolean resolveReflectionTierPriority(ScheduledTask task) {
        if (task != null && !StringValueSupport.isBlank(task.getReflectionModelTier())) {
            return task.isReflectionTierPriority();
        }
        return runtimeConfigService.isAutoReflectionTierPriority();
    }

    private Skill resolveReflectedSkill(String skillName) {
        if (StringValueSupport.isBlank(skillName)) {
            return null;
        }
        return skillComponent.findByName(skillName).orElse(null);
    }

    private String buildGoalPrompt(String prefix, String prompt, String goalId) {
        return "[AUTO] " + prefix + ": " + prompt + " (goal_id: " + goalId + ")";
    }

    private String buildTaskPrompt(String prefix, String prompt, String goalTitle, String goalId, String taskId) {
        return "[AUTO] " + prefix + ": " + prompt + " (goal: " + goalTitle
                + ", goal_id: " + goalId + ", task_id: " + taskId + ")";
    }

    private String buildScheduledTaskPrompt(String prompt, String title, String scheduledTaskId) {
        return "[AUTO] Run scheduled task: " + prompt + " (scheduled_task: " + title
                + ", scheduled_task_id: " + scheduledTaskId + ")";
    }
}
