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
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.tools.GoalManagementTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    private final AutoModeService autoModeService;
    private final ScheduleService scheduleService;
    private final AgentLoop agentLoop;
    private final BotProperties properties;
    private final GoalManagementTool goalManagementTool;
    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();
    private final AtomicBoolean executing = new AtomicBoolean(false);

    // Single channel info for milestone notifications
    private volatile ChannelInfo channelInfo;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public AutoModeScheduler(AutoModeService autoModeService, ScheduleService scheduleService,
            AgentLoop agentLoop, BotProperties properties,
            GoalManagementTool goalManagementTool, List<ChannelPort> channelPorts) {
        this.autoModeService = autoModeService;
        this.scheduleService = scheduleService;
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.goalManagementTool = goalManagementTool;
        for (ChannelPort port : channelPorts) {
            channelRegistry.put(port.getChannelType(), port);
        }
    }

    @PostConstruct
    public void init() {
        if (!properties.getAuto().isEnabled()) {
            log.info("[AutoScheduler] Auto mode disabled");
            return;
        }

        goalManagementTool.setMilestoneCallback(event -> sendMilestoneNotification(event.message()));

        autoModeService.loadState();

        if (properties.getAuto().isAutoStart() && !autoModeService.isAutoModeEnabled()) {
            autoModeService.enableAutoMode();
            log.info("[AutoScheduler] Auto-started auto mode");
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-mode-scheduler");
            t.setDaemon(true);
            return t;
        });

        int tickIntervalSeconds = properties.getAuto().getTickIntervalSeconds();
        tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                tickIntervalSeconds,
                tickIntervalSeconds,
                TimeUnit.SECONDS);

        log.info("[AutoScheduler] Started with tick interval: {}s", tickIntervalSeconds);
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
        channelInfo = new ChannelInfo(channelType, chatId);
        log.debug("[AutoScheduler] Registered channel: {}:{}", channelType, chatId);
    }

    @EventListener
    public void onChannelRegistered(AutoModeChannelRegisteredEvent event) {
        registerChannel(event.channelType(), event.chatId());
    }

    /**
     * Send a milestone notification to the registered channel.
     */
    public void sendMilestoneNotification(String text) {
        if (!properties.getAuto().isNotifyMilestones()) {
            return;
        }

        ChannelInfo info = channelInfo;
        if (info == null) {
            log.debug("[AutoScheduler] No channel info, skipping notification");
            return;
        }

        ChannelPort channel = channelRegistry.get(info.channelType());
        if (channel == null) {
            log.warn("[AutoScheduler] Channel '{}' not found for notification", info.channelType());
            return;
        }

        try {
            channel.sendMessage(info.chatId(), "\uD83E\uDD16 " + text).get(10, TimeUnit.SECONDS);
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

                int taskTimeoutMinutes = properties.getAuto().getTaskTimeoutMinutes();
                for (ScheduleEntry schedule : dueSchedules) {
                    processSchedule(schedule, taskTimeoutMinutes);
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
        try {
            String messageContent = buildMessageForSchedule(schedule);
            if (messageContent == null) {
                log.debug("[AutoScheduler] No action for schedule {}", schedule.getId());
                return;
            }

            log.info("[AutoScheduler] Processing schedule {}: {}", schedule.getId(), messageContent);

            ChannelInfo info = channelInfo;
            String chatId = info != null ? info.chatId() : "auto";
            String channelType = info != null ? info.channelType() : "auto";

            Message syntheticMessage = Message.builder()
                    .role("user")
                    .content(messageContent)
                    .channelType(channelType)
                    .chatId(chatId)
                    .senderId("auto")
                    .metadata(Map.of("auto.mode", true))
                    .timestamp(Instant.now())
                    .build();

            CompletableFuture.runAsync(() -> agentLoop.processMessage(syntheticMessage))
                    .get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.error("[AutoScheduler] Schedule {} timed out after {} minutes",
                    schedule.getId(), timeoutMinutes);
        } catch (Exception e) {
            log.error("[AutoScheduler] Failed to process schedule {}: {}",
                    schedule.getId(), e.getMessage(), e);
        }
    }

    private String buildMessageForSchedule(ScheduleEntry schedule) {
        if (schedule.getType() == ScheduleEntry.ScheduleType.GOAL) {
            return buildGoalMessage(schedule.getTargetId());
        } else if (schedule.getType() == ScheduleEntry.ScheduleType.TASK) {
            return buildTaskMessage(schedule.getTargetId());
        }
        return null;
    }

    private String buildGoalMessage(String goalId) {
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
            return "[AUTO] Continue working on task: " + nextTask.get().getTitle()
                    + " (goal: " + goal.getTitle() + ", goal_id: " + goalId + ")";
        }

        if (goal.getTasks().isEmpty()) {
            return "[AUTO] Plan tasks for goal: " + goal.getTitle()
                    + " (goal_id: " + goalId + ")";
        }

        log.debug("[AutoScheduler] All tasks for goal {} are done, skipping", goalId);
        return null;
    }

    private String buildTaskMessage(String taskId) {
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

        return "[AUTO] Work on task: " + task.getTitle()
                + " (goal: " + goal.getTitle() + ", task_id: " + taskId + ")";
    }

    public record ChannelInfo(String channelType, String chatId) {
    }
}
