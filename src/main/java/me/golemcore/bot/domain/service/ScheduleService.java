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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.model.ScheduleReportConfigUpdate;
import me.golemcore.bot.port.outbound.ScheduleCronPort;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;

/**
 * Domain service for managing cron-based schedules for autonomous goal/task
 * execution.
 */
@Slf4j
public class ScheduleService {

    private final SchedulePersistencePort schedulePersistencePort;
    private final ScheduleCronPort scheduleCronPort;
    private final Clock clock;
    private final AutoModeMigrationService autoModeMigrationService;

    private volatile List<ScheduleEntry> schedulesCache;

    public ScheduleService(
            SchedulePersistencePort schedulePersistencePort,
            ScheduleCronPort scheduleCronPort,
            Clock clock) {
        this(schedulePersistencePort, scheduleCronPort, clock, null);
    }

    public ScheduleService(
            SchedulePersistencePort schedulePersistencePort,
            ScheduleCronPort scheduleCronPort,
            Clock clock,
            AutoModeMigrationService autoModeMigrationService) {
        this.schedulePersistencePort = schedulePersistencePort;
        this.scheduleCronPort = scheduleCronPort;
        this.clock = clock;
        this.autoModeMigrationService = autoModeMigrationService;
    }

    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions) {
        return createSchedule(type, targetId, cronExpression, maxExecutions, false);
    }

    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean clearContextBeforeRun) {
        return createSchedule(type, targetId, cronExpression, maxExecutions, clearContextBeforeRun, null);
    }

    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean clearContextBeforeRun,
            ScheduleReportConfig report) {
        String normalizedCron = normalizeCronExpression(cronExpression);
        Instant now = clock.instant();
        Instant nextExecution = computeNextExecution(normalizedCron, now);

        ScheduleEntry entry = ScheduleEntry.builder()
                .id("sched-" + type.name().toLowerCase(Locale.ROOT) + "-"
                        + UUID.randomUUID().toString().substring(0, 8))
                .type(type)
                .targetId(targetId)
                .cronExpression(normalizedCron)
                .enabled(true)
                .clearContextBeforeRun(clearContextBeforeRun)
                .report(copyReport(report))
                .maxExecutions(maxExecutions)
                .executionCount(0)
                .createdAt(now)
                .updatedAt(now)
                .nextExecutionAt(nextExecution)
                .build();

        List<ScheduleEntry> schedules = getSchedules();
        schedules.add(entry);
        saveSchedules(schedules);
        log.info("[Schedule] Created {} schedule for target {}: {}", type, targetId, normalizedCron);
        return entry;
    }

    public ScheduleEntry updateSchedule(
            String id,
            ScheduleEntry.ScheduleType type,
            String targetId,
            String cronExpression,
            int maxExecutions,
            boolean enabled) {
        return updateSchedule(id, type, targetId, cronExpression, maxExecutions, enabled, null);
    }

    public ScheduleEntry updateSchedule(
            String id,
            ScheduleEntry.ScheduleType type,
            String targetId,
            String cronExpression,
            int maxExecutions,
            boolean enabled,
            Boolean clearContextBeforeRun) {
        return updateSchedule(id, type, targetId, cronExpression, maxExecutions, enabled, clearContextBeforeRun,
                ScheduleReportConfigUpdate.noChange());
    }

    public ScheduleEntry updateSchedule(
            String id,
            ScheduleEntry.ScheduleType type,
            String targetId,
            String cronExpression,
            int maxExecutions,
            boolean enabled,
            Boolean clearContextBeforeRun,
            ScheduleReportConfigUpdate reportUpdate) {
        ScheduleEntry entry = findSchedule(id)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));

        String normalizedCron = normalizeCronExpression(cronExpression);
        Instant now = clock.instant();
        boolean exhausted = maxExecutions > 0 && entry.getExecutionCount() >= maxExecutions;

        entry.setType(type);
        entry.setTargetId(targetId);
        entry.setCronExpression(normalizedCron);
        entry.setMaxExecutions(maxExecutions);
        entry.setUpdatedAt(now);
        entry.setEnabled(enabled && !exhausted);
        if (clearContextBeforeRun != null) {
            entry.setClearContextBeforeRun(clearContextBeforeRun);
        }
        if (reportUpdate != null && reportUpdate.applies()) {
            ScheduleReportConfig report = reportUpdate.clears() ? null : copyReport(reportUpdate.getConfig());
            entry.setReport(report);
        }
        if (entry.isEnabled()) {
            entry.setNextExecutionAt(computeNextExecution(normalizedCron, now));
        } else {
            entry.setNextExecutionAt(null);
        }

        saveSchedules(getSchedules());
        log.info("[Schedule] Updated schedule {} for target {}: {}", id, targetId, normalizedCron);
        return entry;
    }

    public synchronized List<ScheduleEntry> getSchedules() {
        if (schedulesCache == null) {
            if (autoModeMigrationService != null) {
                autoModeMigrationService.migrateIfNeeded();
            }
            schedulesCache = schedulePersistencePort.loadSchedules();
            schedulesCache.forEach(this::normalizeLoadedSchedule);
        }
        return schedulesCache;
    }

    public synchronized void replaceSchedules(List<ScheduleEntry> schedules) {
        List<ScheduleEntry> normalizedSchedules = schedules != null ? new ArrayList<>(schedules) : new ArrayList<>();
        normalizedSchedules.forEach(this::normalizeLoadedSchedule);
        saveSchedules(normalizedSchedules);
    }

    public Optional<ScheduleEntry> findSchedule(String id) {
        return getSchedules().stream()
                .filter(schedule -> schedule.getId().equals(id))
                .findFirst();
    }

    public List<ScheduleEntry> findSchedulesForTarget(String targetId) {
        return getSchedules().stream()
                .filter(schedule -> schedule.getTargetId().equals(targetId))
                .toList();
    }

    public void deleteSchedule(String id) {
        List<ScheduleEntry> schedules = getSchedules();
        boolean removed = schedules.removeIf(schedule -> schedule.getId().equals(id));
        if (!removed) {
            throw new IllegalArgumentException("Schedule not found: " + id);
        }
        saveSchedules(schedules);
        log.info("[Schedule] Deleted schedule: {}", id);
    }

    public List<ScheduleEntry> getDueSchedules() {
        Instant now = clock.instant();
        return getSchedules().stream()
                .filter(ScheduleEntry::isEnabled)
                .filter(schedule -> !schedule.isExhausted())
                .filter(schedule -> schedule.getNextExecutionAt() != null
                        && !schedule.getNextExecutionAt().isAfter(now))
                .toList();
    }

    public void recordExecution(String id) {
        ScheduleEntry entry = findSchedule(id)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));

        Instant now = clock.instant();
        entry.setExecutionCount(entry.getExecutionCount() + 1);
        entry.setLastExecutedAt(now);
        entry.setUpdatedAt(now);

        if (entry.isExhausted()) {
            entry.setEnabled(false);
            entry.setNextExecutionAt(null);
            log.info("[Schedule] Schedule {} exhausted after {} executions", id, entry.getExecutionCount());
        } else {
            entry.setNextExecutionAt(computeNextExecution(entry.getCronExpression(), now));
        }

        saveSchedules(getSchedules());
    }

    String normalizeCronExpression(String input) {
        return scheduleCronPort.normalize(input);
    }

    Instant computeNextExecution(String cronExpression, Instant after) {
        return scheduleCronPort.nextExecution(cronExpression, after);
    }

    private void saveSchedules(List<ScheduleEntry> schedules) {
        schedulePersistencePort.saveSchedules(schedules);
        schedulesCache = schedules;
    }

    private void normalizeLoadedSchedule(ScheduleEntry entry) {
        ScheduleReportConfig report = entry.getReport();
        if (report == null) {
            return;
        }
        boolean emptyChannel = StringValueSupport.isBlank(report.getChannelType());
        boolean emptyChat = StringValueSupport.isBlank(report.getChatId());
        boolean emptyWebhookUrl = StringValueSupport.isBlank(report.getWebhookUrl());
        boolean emptyWebhookToken = StringValueSupport.isBlank(report.getWebhookBearerToken());
        if (emptyChannel && emptyChat && emptyWebhookUrl && emptyWebhookToken) {
            entry.setReport(null);
        }
    }

    private static ScheduleReportConfig copyReport(ScheduleReportConfig report) {
        if (report == null) {
            return null;
        }
        return ScheduleReportConfig.builder()
                .channelType(report.getChannelType())
                .chatId(report.getChatId())
                .webhookUrl(report.getWebhookUrl())
                .webhookBearerToken(report.getWebhookBearerToken())
                .build();
    }
}
