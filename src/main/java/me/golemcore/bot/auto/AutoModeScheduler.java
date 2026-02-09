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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.AutoModeService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scheduler for autonomous mode that periodically triggers goal-driven agent
 * work.
 *
 * <p>
 * This component runs a background thread that:
 * <ul>
 * <li>Checks for active goals at configured intervals (default 30 minutes)</li>
 * <li>Sends synthetic messages to the agent loop to trigger autonomous
 * work</li>
 * <li>Sends milestone notifications to configured channels</li>
 * <li>Loads and persists auto mode state (goals, tasks, diary)</li>
 * </ul>
 *
 * <p>
 * The scheduler is single-user (no per-user partitioning) and creates messages
 * marked with {@code metadata["auto.mode"] = true} to distinguish them from
 * user input.
 *
 * <p>
 * Only runs if {@code bot.auto.enabled=true}. Gracefully shuts down on
 * application stop.
 *
 * @since 1.0
 * @see AutoModeService
 * @see GoalManagementTool
 */
@Component
@Slf4j
public class AutoModeScheduler {

    private final AutoModeService autoModeService;
    private final AgentLoop agentLoop;
    private final BotProperties properties;
    private final GoalManagementTool goalManagementTool;
    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();

    // Single channel info for milestone notifications
    private volatile ChannelInfo channelInfo;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickTask;

    public AutoModeScheduler(AutoModeService autoModeService, AgentLoop agentLoop,
            BotProperties properties, GoalManagementTool goalManagementTool,
            List<ChannelPort> channelPorts) {
        this.autoModeService = autoModeService;
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

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-mode-scheduler");
            t.setDaemon(true);
            return t;
        });

        int intervalMinutes = properties.getAuto().getIntervalMinutes();
        tickTask = scheduler.scheduleAtFixedRate(
                this::tick,
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES);

        log.info("[AutoScheduler] Started with interval: {} minutes", intervalMinutes);
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

            log.info("[AutoScheduler] Tick: processing auto mode");
            // Run with timeout to prevent blocking the scheduler thread indefinitely
            CompletableFuture.runAsync(this::processAutoTick)
                    .get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.error("[AutoScheduler] Tick timed out after 5 minutes");
        } catch (Exception e) {
            log.error("[AutoScheduler] Tick failed: {}", e.getMessage(), e);
        }
    }

    private void processAutoTick() {
        var activeGoals = autoModeService.getActiveGoals();
        if (activeGoals.isEmpty()) {
            log.debug("[AutoScheduler] No active goals, skipping");
            return;
        }

        var nextTask = autoModeService.getNextPendingTask();
        String messageContent;

        if (nextTask.isPresent()) {
            // Work on existing task
            messageContent = "[AUTO] Continue working on task: " + nextTask.get().getTitle();
        } else {
            // Find goals needing task planning
            var unplanned = activeGoals.stream()
                    .filter(g -> g.getTasks().isEmpty())
                    .toList();
            if (unplanned.isEmpty()) {
                log.debug("[AutoScheduler] All goals have tasks and all are done, skipping");
                return;
            }
            messageContent = "[AUTO] Plan tasks for goal: " + unplanned.get(0).getTitle()
                    + " (goal_id: " + unplanned.get(0).getId() + ")";
        }

        log.info("[AutoScheduler] Sending: {}", messageContent);

        // Use registered channel for chatId or fallback to "auto"
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

        agentLoop.processMessage(syntheticMessage);
    }

    public record ChannelInfo(String channelType, String chatId) {
    }
}
