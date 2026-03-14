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

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AutoRunKind;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.MdcSupport;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
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
 * <li>Sends synthetic messages to the agent loop for due goals/tasks</li>
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
    private final AgentLoop agentLoop;
    private final RuntimeConfigService runtimeConfigService;
    private final GoalManagementTool goalManagementTool;
    private final ChannelRegistry channelRegistry;
    private final SessionPort sessionPort;
    private final AtomicBoolean executing = new AtomicBoolean(false);

    // Single channel info for milestone notifications
    private volatile ChannelInfo channelInfo;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public AutoModeScheduler(AutoModeService autoModeService, ScheduleService scheduleService,
            AgentLoop agentLoop, RuntimeConfigService runtimeConfigService,
            GoalManagementTool goalManagementTool, ChannelRegistry channelRegistry, SessionPort sessionPort) {
        this.autoModeService = autoModeService;
        this.scheduleService = scheduleService;
        this.agentLoop = agentLoop;
        this.runtimeConfigService = runtimeConfigService;
        this.goalManagementTool = goalManagementTool;
        this.channelRegistry = channelRegistry;
        this.sessionPort = sessionPort;
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
        Map<String, String> mdcContext = AutoRunContextSupport.buildMdcContext(
                channelType,
                sessionChatId,
                transportChatId,
                schedule.getId(),
                runId,
                scheduleMessage.goalId(),
                scheduleMessage.taskId());

        try (MdcSupport.Scope ignored = MdcSupport.withContext(mdcContext)) {
            log.info("[AutoScheduler] Processing schedule {}: {}", schedule.getId(), scheduleMessage.content());

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

            Message syntheticMessage = Message.builder()
                    .role("user")
                    .content(scheduleMessage.content())
                    .channelType(channelType)
                    .chatId(sessionChatId)
                    .senderId("auto")
                    .metadata(metadata)
                    .timestamp(Instant.now())
                    .build();

            Map<String, String> asyncContext = MdcSupport.capture();
            CompletableFuture.runAsync(() -> MdcSupport.runWithContext(
                    asyncContext,
                    () -> agentLoop.processMessage(syntheticMessage))).get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.error("[AutoScheduler] Schedule {} timed out after {} minutes",
                    schedule.getId(), timeoutMinutes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[AutoScheduler] Schedule {} interrupted: {}",
                    schedule.getId(), e.getMessage(), e);
        } catch (ExecutionException e) {
            log.error("[AutoScheduler] Failed to process schedule {}: {}",
                    schedule.getId(), e.getMessage(), e);
        }
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
                .filter(t -> t.getStatus() == AutoTask.TaskStatus.PENDING)
                .min(java.util.Comparator.comparingInt(AutoTask::getOrder));

        if (nextTask.isPresent()) {
            AutoTask task = nextTask.get();
            return new ScheduleMessage(
                    buildTaskPrompt("Continue working on task", task.getExecutionPrompt(), goal.getTitle(),
                            goalId, task.getId()),
                    AutoRunKind.GOAL_RUN,
                    goalId,
                    task.getId());
        }

        if (goal.getTasks().isEmpty()) {
            return new ScheduleMessage(
                    buildGoalPrompt("Plan tasks for goal", goal.getExecutionPrompt(), goalId),
                    AutoRunKind.GOAL_RUN,
                    goalId,
                    null);
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
                taskId);
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

    private record ScheduleMessage(String content, AutoRunKind runKind, String goalId, String taskId) {
    }
}
