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

import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.auto.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.scheduling.ScheduleService;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.channel.ChannelPort;
import me.golemcore.bot.port.outbound.AutoExecutionStatusPort;
import me.golemcore.bot.tools.GoalManagementTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduler for autonomous mode that periodically evaluates cron-based
 * schedules and dispatches them to the scheduled run executor.
 */
@Component
@Slf4j
public class AutoModeScheduler implements AutoExecutionStatusPort {

    private static final int FIXED_TICK_INTERVAL_SECONDS = 1;

    private final AutoModeService autoModeService;
    private final ScheduleService scheduleService;
    private final RuntimeConfigService runtimeConfigService;
    private final GoalManagementTool goalManagementTool;
    private final ChannelRegistry channelRegistry;
    private final ScheduledRunExecutor scheduledRunExecutor;
    private final AtomicBoolean executing = new AtomicBoolean(false);
    private final AtomicReference<ScheduleDeliveryContext> deliveryContext = new AtomicReference<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public AutoModeScheduler(
            AutoModeService autoModeService,
            ScheduleService scheduleService,
            RuntimeConfigService runtimeConfigService,
            GoalManagementTool goalManagementTool,
            ChannelRegistry channelRegistry,
            ScheduledRunExecutor scheduledRunExecutor) {
        this.autoModeService = autoModeService;
        this.scheduleService = scheduleService;
        this.runtimeConfigService = runtimeConfigService;
        this.goalManagementTool = goalManagementTool;
        this.channelRegistry = channelRegistry;
        this.scheduledRunExecutor = scheduledRunExecutor;
    }

    public ScheduleDeliveryContext getDeliveryContext() {
        return deliveryContext.get();
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

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-mode-scheduler");
            thread.setDaemon(true);
            return thread;
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
     * Register channel info for milestone notifications and report auto-resolution.
     */
    public void registerChannel(String channelType, String chatId) {
        registerChannel(channelType, chatId, chatId);
    }

    public void registerChannel(String channelType, String sessionChatId, String transportChatId) {
        deliveryContext.set(new ScheduleDeliveryContext(channelType, sessionChatId, transportChatId));
        log.debug("[AutoScheduler] Registered channel: {} session={} transport={}",
                channelType, sessionChatId, transportChatId);
    }

    public boolean isExecuting() {
        return executing.get();
    }

    @Override
    public boolean isAutoJobExecuting() {
        return isExecuting();
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

        ScheduleDeliveryContext currentDeliveryContext = deliveryContext.get();
        if (currentDeliveryContext == null) {
            log.debug("[AutoScheduler] No channel info, skipping notification");
            return;
        }

        ChannelPort channel = channelRegistry.get(currentDeliveryContext.channelType()).orElse(null);
        if (channel == null) {
            log.warn("[AutoScheduler] Channel '{}' not found for notification",
                    currentDeliveryContext.channelType());
            return;
        }

        try {
            channel.sendMessage(currentDeliveryContext.transportChatId(), "\uD83E\uDD16 " + text)
                    .get(10, TimeUnit.SECONDS);
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
                int taskTimeLimitMinutes = runtimeConfigService.getAutoTaskTimeLimitMinutes();
                boolean loggedDueSchedules = false;
                Set<String> busyScheduledTaskIds = new HashSet<>();
                Set<String> processedScheduleIds = new HashSet<>();
                while (true) {
                    List<ScheduleEntry> dueSchedules = scheduleService.getDueSchedules();
                    if (dueSchedules.isEmpty()) {
                        return;
                    }
                    if (!loggedDueSchedules) {
                        log.info("[AutoScheduler] Tick: {} due schedules", dueSchedules.size());
                        loggedDueSchedules = true;
                    }

                    ScheduleEntry schedule = dueSchedules.stream()
                            .filter(entry -> !processedScheduleIds.contains(entry.getId()))
                            .filter(entry -> !busyScheduledTaskIds.contains(resolveBusyScheduledTaskId(entry)))
                            .findFirst()
                            .orElse(null);
                    if (schedule == null) {
                        return;
                    }
                    String scheduledTaskId = resolveBusyScheduledTaskId(schedule);
                    ScheduledRunOutcome outcome = scheduledRunExecutor.executeSchedule(
                            schedule,
                            deliveryContext.get(),
                            taskTimeLimitMinutes);
                    if (scheduledTaskId != null) {
                        busyScheduledTaskIds.add(scheduledTaskId);
                    }
                    handleRunOutcome(schedule, outcome);
                    processedScheduleIds.add(schedule.getId());
                }
            } finally {
                executing.set(false);
            }
        } catch (Exception e) {
            executing.set(false);
            log.error("[AutoScheduler] Tick failed: {}", e.getMessage(), e);
        }
    }

    private void handleRunOutcome(ScheduleEntry schedule, ScheduledRunOutcome outcome) {
        switch (outcome) {
        case EXECUTED -> scheduleService.recordExecution(schedule.getId());
        case SKIPPED_TARGET_MISSING -> {
            scheduleService.disableSchedule(schedule.getId());
            log.warn("[AutoScheduler] Disabled schedule {} after permanent skip: {}", schedule.getId(), outcome);
        }
        case SKIPPED_TASK_BUSY ->
            log.info("[AutoScheduler] Schedule {} skipped because scheduled task {} is already running",
                    schedule.getId(), schedule.getTargetId());
        case FAILED -> {
            scheduleService.recordFailedAttempt(schedule.getId());
            log.warn("[AutoScheduler] Schedule {} failed; next execution was recalculated", schedule.getId());
        }
        }
    }

    private String resolveBusyScheduledTaskId(ScheduleEntry schedule) {
        if (schedule == null || schedule.getType() != ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
            return null;
        }
        String targetId = schedule.getTargetId();
        return targetId != null && !targetId.isBlank() ? targetId : null;
    }
}
