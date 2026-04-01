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

import me.golemcore.bot.domain.model.AutoRunKind;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.service.TraceContextSupport;
import me.golemcore.bot.domain.service.TraceNamingSupport;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.tools.GoalManagementTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler for autonomous mode that periodically evaluates cron-based
 * schedules and triggers goal/task-driven agent work.
 *
 * <p>
 * This component runs a background thread that:
 * <ul>
 * <li>Ticks at a configurable interval (default 30 seconds)</li>
 * <li>Checks for due schedules via {@link ScheduleService}</li>
 * <li>Submits synthetic messages through {@link SessionRunCoordinator} for due
 * goals/tasks</li>
 * <li>Sends milestone notifications to configured channels</li>
 * </ul>
 *
 * <p>
 * Execution is non-interruptible: if a tick is already processing, subsequent
 * ticks are skipped until the current one completes.
 *
 * @since 1.0
 * @see AutoModeService
 * @see ScheduleService
 * @see GoalManagementTool
 */
@Component
@Slf4j
public class AutoModeScheduler {

    private static final int FIXED_TICK_INTERVAL_SECONDS = 1;

    private final AutoModeService autoModeService;
    private final ScheduleService scheduleService;
    private final SessionRunCoordinator sessionRunCoordinator;
    private final RuntimeConfigService runtimeConfigService;
    private final GoalManagementTool goalManagementTool;
    private final ChannelRegistry channelRegistry;
    private final SessionPort sessionPort;
    private final me.golemcore.bot.domain.component.SkillComponent skillComponent;
    private final ScheduleReportSender reportSender;
    private final AtomicBoolean executing = new AtomicBoolean(false);

    // Single channel info for milestone notifications
    private volatile ChannelInfo channelInfo;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public AutoModeScheduler(AutoModeService autoModeService, ScheduleService scheduleService,
            SessionRunCoordinator sessionRunCoordinator, RuntimeConfigService runtimeConfigService,
            GoalManagementTool goalManagementTool, ChannelRegistry channelRegistry, SessionPort sessionPort,
            me.golemcore.bot.domain.component.SkillComponent skillComponent,
            ScheduleReportSender reportSender) {
        this.autoModeService = autoModeService;
        this.scheduleService = scheduleService;
        this.sessionRunCoordinator = sessionRunCoordinator;
        this.runtimeConfigService = runtimeConfigService;
        this.goalManagementTool = goalManagementTool;
        this.channelRegistry = channelRegistry;
        this.sessionPort = sessionPort;
        this.skillComponent = skillComponent;
        this.reportSender = reportSender;
    }

    public ChannelInfo getChannelInfo() {
        return channelInfo;
    }

    @PostConstruct
    public void init() {
        goalManagementTool.setMilestoneCallback(event -> sendMilestoneNotification(event.message()));

        autoModeService.loadState();

        boolean featureEnabled = runtimeConfigService.isAutoModeEnabled();
        if (featureEnabled && runtimeConfigService.isAutoStartEnabled() && !autoModeService.isAutoModeEnabled()) {
            autoModeService.enableAutoMode();
            log.info("[AutoScheduler] Auto-started auto mode");
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-mode-scheduler");
            t.setDaemon(true);
            return t;
        });

        int tickIntervalSeconds = FIXED_TICK_INTERVAL_SECONDS;
        tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                tickIntervalSeconds,
                tickIntervalSeconds,
                TimeUnit.SECONDS);

        log.info("[AutoScheduler] Started with tick interval: {}s", tickIntervalSeconds);
        if (!featureEnabled) {
            log.info("[AutoScheduler] Auto mode feature disabled in runtime config; scheduler is idle");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("[AutoScheduler] Shut down");
    }

    /**
     * Register channel info for milestone notifications.
     */
    public void registerChannel(String channelType, String chatId) {
        registerChannel(channelType, chatId, chatId);
    }

    public void registerChannel(String channelType, String sessionChatId, String transportChatId) {
        channelInfo = new ChannelInfo(channelType, sessionChatId, transportChatId);
        log.debug("[AutoScheduler] Registered channel: {} session={} transport={}",
                channelType, sessionChatId, transportChatId);
    }

    public boolean isExecuting() {
        return executing.get();
    }

    @EventListener
    public void onChannelRegistered(AutoModeChannelRegisteredEvent event) {
        registerChannel(event.channelType(), event.sessionChatId(), event.transportChatId());
    }

    /**
     * Send a milestone notification to the registered channel.
     */
    public void sendMilestoneNotification(String text) {
        if (!runtimeConfigService.isAutoNotifyMilestonesEnabled()) {
            return;
        }

        ChannelInfo info = channelInfo;
        if (info == null) {
            log.debug("[AutoScheduler] No channel info, skipping notification");
            return;
        }

        ChannelPort channel = channelRegistry.get(info.channelType()).orElse(null);
        if (channel == null) {
            log.warn("[AutoScheduler] Channel '{}' not found for notification", info.channelType());
            return;
        }

        try {
            channel.sendMessage(info.transportChatId(), "\uD83E\uDD16 " + text).get(10, TimeUnit.SECONDS);
            log.info("[AutoScheduler] Sent milestone notification: {}", text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[AutoScheduler] Failed to send notification: {}", e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.error("[AutoScheduler] Failed to send notification: {}", e.getMessage());
        }
    }

    void tick() {
        try {
            if (!runtimeConfigService.isAutoModeEnabled()) {
                return;
            }

            if (!autoModeService.isAutoModeEnabled()) {
                return;
            }

            if (!executing.compareAndSet(false, true)) {
                log.debug("[AutoScheduler] Tick skipped: previous execution still in progress");
                return;
            }

            try {
                List<ScheduleEntry> dueSchedules = scheduleService.getDueSchedules();
                if (dueSchedules.isEmpty()) {
                    return;
                }

                log.info("[AutoScheduler] Tick: {} due schedules", dueSchedules.size());

                int taskTimeLimitMinutes = runtimeConfigService.getAutoTaskTimeLimitMinutes();
                for (ScheduleEntry schedule : dueSchedules) {
                    processSchedule(schedule, taskTimeLimitMinutes);
                    scheduleService.recordExecution(schedule.getId());
                }
            } finally {
                executing.set(false);
            }
        } catch (Exception e) {
            executing.set(false);
            log.error("[AutoScheduler] Tick failed: {}", e.getMessage(), e);
        }
    }

    private void processSchedule(ScheduleEntry schedule, int timeoutMinutes) {
        ScheduleMessage scheduleMessage = buildMessageForSchedule(schedule);
        if (scheduleMessage == null) {
            log.debug("[AutoScheduler] No action for schedule {}", schedule.getId());
            return;
        }

        ChannelInfo info = channelInfo;
        String sessionChatId = info != null ? info.sessionChatId() : "auto";
        String transportChatId = info != null ? info.transportChatId() : sessionChatId;
        String channelType = info != null ? info.channelType() : "auto";
        String runId = UUID.randomUUID().toString();
        if (schedule.isClearContextBeforeRun()) {
            clearSessionContext(channelType, sessionChatId, schedule.getId());
        }

        try {
            log.info("[AutoScheduler] Processing schedule {}: {}", schedule.getId(), scheduleMessage.content());
            submitAndAwait(scheduleMessage, schedule, timeoutMinutes, channelType, sessionChatId, transportChatId,
                    runId);
        } catch (TimeoutException e) {
            recordFailureAndMaybeReflect(scheduleMessage, schedule, timeoutMinutes, channelType, sessionChatId,
                    transportChatId,
                    "Run timed out after " + timeoutMinutes + " minutes",
                    "timeout",
                    null);
            log.error("[AutoScheduler] Schedule {} timed out after {} minutes",
                    schedule.getId(), timeoutMinutes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailureAndMaybeReflect(scheduleMessage, schedule, timeoutMinutes, channelType, sessionChatId,
                    transportChatId,
                    e.getMessage(),
                    "interrupted",
                    null);
            log.error("[AutoScheduler] Schedule {} interrupted: {}",
                    schedule.getId(), e.getMessage(), e);
        } catch (ExecutionException e) {
            recordFailureAndMaybeReflect(scheduleMessage, schedule, timeoutMinutes, channelType, sessionChatId,
                    transportChatId,
                    e.getMessage(),
                    "execution_exception",
                    null);
            log.error("[AutoScheduler] Failed to process schedule {}: {}",
                    schedule.getId(), e.getMessage(), e);
        }
    }

    private void submitAndAwait(ScheduleMessage scheduleMessage, ScheduleEntry schedule, int timeoutMinutes,
            String channelType, String sessionChatId, String transportChatId, String runId)
            throws InterruptedException, ExecutionException, TimeoutException {
        Message syntheticMessage = buildSyntheticMessage(scheduleMessage, schedule, channelType, sessionChatId,
                transportChatId, runId);
        CompletableFuture<Void> completion = sessionRunCoordinator.submit(syntheticMessage);
        completion.get(timeoutMinutes, TimeUnit.MINUTES);

        String runStatus = readStatus(syntheticMessage);
        String assistantText = readAssistantText(syntheticMessage);
        String activeSkillName = readActiveSkillName(syntheticMessage);
        if ("FAILED".equals(runStatus)) {
            recordFailureAndMaybeReflect(scheduleMessage, schedule, timeoutMinutes, channelType, sessionChatId,
                    transportChatId,
                    readFailureSummary(syntheticMessage),
                    readFailureFingerprint(syntheticMessage),
                    activeSkillName);
            return;
        }

        if (scheduleMessage.reflectionActive()) {
            autoModeService.applyReflectionResult(scheduleMessage.goalId(), scheduleMessage.taskId(), assistantText);
            return;
        }

        handleRunSuccess(scheduleMessage, activeSkillName);
        reportSender.sendReport(schedule, buildReportHeader(scheduleMessage), assistantText, channelInfo);
    }

    private void recordFailureAndMaybeReflect(ScheduleMessage scheduleMessage, ScheduleEntry schedule,
            int timeoutMinutes, String channelType, String sessionChatId, String transportChatId,
            String summary, String fingerprint, String activeSkillName) {
        recordFailedRunState(scheduleMessage, summary, fingerprint, activeSkillName);
        if (!shouldRunReflection(scheduleMessage)) {
            return;
        }

        try {
            runReflection(scheduleMessage, schedule, timeoutMinutes, channelType, sessionChatId, transportChatId);
        } catch (TimeoutException e) {
            recordFailedRunState(scheduleMessage,
                    "Reflection timed out after " + timeoutMinutes + " minutes",
                    "reflection_timeout",
                    null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailedRunState(scheduleMessage, e.getMessage(), "reflection_interrupted", null);
        } catch (ExecutionException e) {
            recordFailedRunState(scheduleMessage, e.getMessage(), "reflection_execution_exception", null);
        }
    }

    private void runReflection(ScheduleMessage scheduleMessage, ScheduleEntry schedule, int timeoutMinutes,
            String channelType, String sessionChatId, String transportChatId)
            throws InterruptedException, ExecutionException, TimeoutException {
        ScheduleMessage reflectionMessage = buildReflectionMessage(scheduleMessage, schedule.getId());
        if (reflectionMessage == null) {
            return;
        }

        Message syntheticMessage = buildSyntheticMessage(reflectionMessage, schedule, channelType, sessionChatId,
                transportChatId, UUID.randomUUID().toString());
        CompletableFuture<Void> completion = sessionRunCoordinator.submit(syntheticMessage);
        completion.get(timeoutMinutes, TimeUnit.MINUTES);

        String runStatus = readStatus(syntheticMessage);
        if ("FAILED".equals(runStatus)) {
            recordFailedRunState(reflectionMessage,
                    readFailureSummary(syntheticMessage),
                    readFailureFingerprint(syntheticMessage),
                    readActiveSkillName(syntheticMessage));
            return;
        }

        autoModeService.applyReflectionResult(reflectionMessage.goalId(), reflectionMessage.taskId(),
                readAssistantText(syntheticMessage));
    }

    private Message buildSyntheticMessage(ScheduleMessage scheduleMessage, ScheduleEntry schedule,
            String channelType, String sessionChatId, String transportChatId, String runId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.AUTO_MODE, true);
        metadata.put(ContextAttributes.AUTO_RUN_KIND, scheduleMessage.runKind().name());
        metadata.put(ContextAttributes.AUTO_RUN_ID, runId);
        metadata.put(ContextAttributes.AUTO_SCHEDULE_ID, schedule.getId());
        metadata.put(ContextAttributes.CONVERSATION_KEY, sessionChatId);
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
        if (scheduleMessage.goalId() != null && !scheduleMessage.goalId().isBlank()) {
            metadata.put(ContextAttributes.AUTO_GOAL_ID, scheduleMessage.goalId());
        }
        if (scheduleMessage.taskId() != null && !scheduleMessage.taskId().isBlank()) {
            metadata.put(ContextAttributes.AUTO_TASK_ID, scheduleMessage.taskId());
        }
        if (scheduleMessage.reflectionActive()) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }
        if (scheduleMessage.reflectionTier() != null && !scheduleMessage.reflectionTier().isBlank()) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_TIER, scheduleMessage.reflectionTier());
        }
        metadata.put(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, scheduleMessage.reflectionTierPriority());
        metadata = TraceContextSupport.ensureRootMetadata(
                metadata,
                TraceSpanKind.INTERNAL,
                TraceNamingSupport.autoSchedule(schedule));

        return Message.builder()
                .role("user")
                .content(scheduleMessage.content())
                .channelType(channelType)
                .chatId(sessionChatId)
                .senderId("auto")
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
    }

    private static String buildReportHeader(ScheduleMessage scheduleMessage) {
        StringBuilder header = new StringBuilder("\uD83D\uDCCB ");
        if (!StringValueSupport.isBlank(scheduleMessage.goalTitle())) {
            header.append("Goal: ").append(scheduleMessage.goalTitle());
            if (!StringValueSupport.isBlank(scheduleMessage.taskTitle())) {
                header.append(" / Task: ").append(scheduleMessage.taskTitle());
            }
        } else if (!StringValueSupport.isBlank(scheduleMessage.taskTitle())) {
            header.append("Task: ").append(scheduleMessage.taskTitle());
        } else {
            header.append("Scheduled run report");
        }
        return header.toString();
    }

    private void handleRunSuccess(ScheduleMessage scheduleMessage, String activeSkillName) {
        if (!scheduleMessage.reflectionActive() && !StringValueSupport.isBlank(scheduleMessage.goalId())) {
            autoModeService.recordAutoRunSuccess(scheduleMessage.goalId(), scheduleMessage.taskId(), activeSkillName);
        }
    }

    private void recordFailedRunState(ScheduleMessage scheduleMessage, String summary, String fingerprint,
            String activeSkillName) {
        if (StringValueSupport.isBlank(scheduleMessage.goalId())) {
            return;
        }
        autoModeService.recordAutoRunFailure(scheduleMessage.goalId(), scheduleMessage.taskId(), summary, fingerprint,
                activeSkillName);
    }

    private boolean shouldRunReflection(ScheduleMessage scheduleMessage) {
        if (scheduleMessage.reflectionActive()) {
            return false;
        }
        if (!runtimeConfigService.isAutoReflectionEnabled()) {
            return false;
        }
        if (StringValueSupport.isBlank(scheduleMessage.goalId())) {
            return false;
        }
        return autoModeService.shouldTriggerReflection(scheduleMessage.goalId(), scheduleMessage.taskId());
    }

    private ScheduleMessage buildReflectionMessage(ScheduleMessage source, String scheduleId) {
        if (StringValueSupport.isBlank(source.goalId())) {
            return null;
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
                    .append("' and propose an alternative strategy for the next run.");
        } else {
            prompt.append("[AUTO][REFLECTION] Analyze the repeated failure while planning goal '")
                    .append(source.goalTitle() != null ? source.goalTitle() : source.goalId())
                    .append("' and propose an alternative strategy for the next run.");
        }
        if (!StringValueSupport.isBlank(reflectionState.lastFailureSummary())) {
            prompt.append(" Latest failure: ").append(reflectionState.lastFailureSummary()).append('.');
        }
        if (!StringValueSupport.isBlank(reflectionState.lastFailureFingerprint())) {
            prompt.append(" Failure fingerprint: ").append(reflectionState.lastFailureFingerprint()).append('.');
        }
        prompt.append(" Schedule: ").append(scheduleId).append('.');
        prompt.append(" Return a concise recovery strategy that changes the next approach.");

        return new ScheduleMessage(
                prompt.toString(),
                source.runKind(),
                source.goalId(),
                source.taskId(),
                source.goalTitle(),
                source.taskTitle(),
                true,
                tier,
                priority);
    }

    private String readStatus(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_STATUS)
                : null;
    }

    private String readFailureSummary(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY)
                : null;
    }

    private String readFailureFingerprint(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT)
                : null;
    }

    private String readAssistantText(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT)
                : null;
    }

    private String readActiveSkillName(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_ACTIVE_SKILL)
                : null;
    }

    private void clearSessionContext(String channelType, String sessionChatId, String scheduleId) {
        String sessionId = sessionPort.getOrCreate(channelType, sessionChatId).getId();
        sessionPort.clearMessages(sessionId);
        log.info("[AutoScheduler] Cleared session context before schedule run: scheduleId={}, sessionId={}",
                scheduleId, sessionId);
    }

    private ScheduleMessage buildMessageForSchedule(ScheduleEntry schedule) {
        if (schedule.getType() == ScheduleEntry.ScheduleType.GOAL) {
            return buildGoalMessage(schedule.getTargetId());
        } else if (schedule.getType() == ScheduleEntry.ScheduleType.TASK) {
            return buildTaskMessage(schedule.getTargetId());
        }
        return null;
    }

    private ScheduleMessage buildGoalMessage(String goalId) {
        Optional<Goal> goalOpt = autoModeService.getGoal(goalId);
        if (goalOpt.isEmpty()) {
            log.warn("[AutoScheduler] Goal not found for schedule: {}", goalId);
            return null;
        }

        Goal goal = goalOpt.get();
        if (goal.getStatus() != Goal.GoalStatus.ACTIVE) {
            log.debug("[AutoScheduler] Goal {} is not active ({}), skipping", goalId, goal.getStatus());
            return null;
        }

        Optional<AutoTask> nextTask = goal.getTasks().stream()
                .filter(t -> t.getStatus() == AutoTask.TaskStatus.PENDING
                        || t.getStatus() == AutoTask.TaskStatus.IN_PROGRESS
                        || t.getStatus() == AutoTask.TaskStatus.FAILED)
                .min(java.util.Comparator.comparingInt(AutoTask::getOrder));

        if (nextTask.isPresent()) {
            AutoTask task = nextTask.get();
            return new ScheduleMessage(
                    buildTaskPrompt("Continue working on task", task.getExecutionPrompt(), goal.getTitle(),
                            goalId, task.getId()),
                    AutoRunKind.GOAL_RUN,
                    goalId,
                    task.getId(),
                    goal.getTitle(),
                    task.getTitle(),
                    false,
                    resolveReflectionTier(goal, task),
                    resolveReflectionTierPriority(goal, task));
        }

        if (goal.getTasks().isEmpty()) {
            return new ScheduleMessage(
                    buildGoalPrompt("Plan tasks for goal", goal.getExecutionPrompt(), goalId),
                    AutoRunKind.GOAL_RUN,
                    goalId,
                    null,
                    goal.getTitle(),
                    null,
                    false,
                    goal.getReflectionModelTier(),
                    goal.isReflectionTierPriority());
        }

        log.debug("[AutoScheduler] All tasks for goal {} are done, skipping", goalId);
        return null;
    }

    private ScheduleMessage buildTaskMessage(String taskId) {
        Optional<Goal> goalOpt = autoModeService.findGoalForTask(taskId);
        if (goalOpt.isEmpty()) {
            log.warn("[AutoScheduler] Task not found: {}", taskId);
            return null;
        }

        Goal goal = goalOpt.get();
        Optional<AutoTask> taskOpt = goal.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst();

        if (taskOpt.isEmpty()) {
            return null;
        }

        AutoTask task = taskOpt.get();
        if (task.getStatus() == AutoTask.TaskStatus.COMPLETED
                || task.getStatus() == AutoTask.TaskStatus.SKIPPED) {
            log.debug("[AutoScheduler] Task {} already finished ({}), skipping", taskId, task.getStatus());
            return null;
        }

        return new ScheduleMessage(
                buildTaskPrompt("Work on task", task.getExecutionPrompt(), goal.getTitle(), goal.getId(), taskId),
                AutoRunKind.GOAL_RUN,
                goal.getId(),
                taskId,
                goal.getTitle(),
                task.getTitle(),
                false,
                resolveReflectionTier(goal, task),
                resolveReflectionTierPriority(goal, task));
    }

    private String resolveReflectionTier(Goal goal, AutoTask task) {
        if (task != null && task.getReflectionModelTier() != null && !task.getReflectionModelTier().isBlank()) {
            return task.getReflectionModelTier();
        }
        if (goal != null && goal.getReflectionModelTier() != null && !goal.getReflectionModelTier().isBlank()) {
            return goal.getReflectionModelTier();
        }
        return runtimeConfigService.getAutoReflectionModelTier();
    }

    private boolean resolveReflectionTierPriority(Goal goal, AutoTask task) {
        if (task != null && task.getReflectionModelTier() != null && !task.getReflectionModelTier().isBlank()) {
            return task.isReflectionTierPriority();
        }
        if (goal != null && goal.getReflectionModelTier() != null && !goal.getReflectionModelTier().isBlank()) {
            return goal.isReflectionTierPriority();
        }
        return runtimeConfigService.isAutoReflectionTierPriority();
    }

    private Skill resolveReflectedSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
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

    public record ChannelInfo(String channelType, String sessionChatId, String transportChatId) {
    }

    private record ScheduleMessage(String content, AutoRunKind runKind, String goalId, String taskId,
            String goalTitle, String taskTitle, boolean reflectionActive, String reflectionTier,
            boolean reflectionTierPriority) {
    }
}
