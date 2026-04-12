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
 * execution. Schedules are persisted in {@code auto/schedules.json} via
 * {@link StoragePort}.
 *
 * <p>
 * Only GOAL and TASK schedule types exist. Goals/tasks without explicit
 * schedules are never auto-processed.
 */
@Slf4j
public class ScheduleService {

    private final SchedulePersistencePort schedulePersistencePort;
    private final ScheduleCronPort scheduleCronPort;
    private final Clock clock;

    private volatile List<ScheduleEntry> schedulesCache;

    public ScheduleService(
            SchedulePersistencePort schedulePersistencePort,
            ScheduleCronPort scheduleCronPort,
            Clock clock) {
        this.schedulePersistencePort = schedulePersistencePort;
        this.scheduleCronPort = scheduleCronPort;
        this.clock = clock;
    }

    /**
     * Create a new schedule entry. Validates the cron expression, computes the next
     * execution time, and persists.
     */
    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions) {
        return createSchedule(type, targetId, cronExpression, maxExecutions, false);
    }

    /**
     * Create a new schedule entry. Validates the cron expression, computes the next
     * execution time, and persists.
     */
    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean clearContextBeforeRun) {
        return createSchedule(type, targetId, cronExpression, maxExecutions, clearContextBeforeRun, null);
    }

    /**
     * Create a new schedule entry with nested report configuration.
     */
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

    /**
     * Update an existing schedule entry.
     */
    public ScheduleEntry updateSchedule(
            String id,
            ScheduleEntry.ScheduleType type,
            String targetId,
            String cronExpression,
            int maxExecutions,
            boolean enabled) {
        return updateSchedule(id, type, targetId, cronExpression, maxExecutions, enabled, null);
    }

    /**
     * Update an existing schedule entry.
     */
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

    /**
     * Update an existing schedule entry with explicit report config semantics.
     */
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

    /**
     * Get all schedules (cached, loaded on first access).
     */
    public synchronized List<ScheduleEntry> getSchedules() {
        if (schedulesCache == null) {
            schedulesCache = schedulePersistencePort.loadSchedules();
            schedulesCache.forEach(this::normalizeLoadedSchedule);
        }
        return schedulesCache;
    }

    /**
     * Find a schedule by its ID.
     */
    public Optional<ScheduleEntry> findSchedule(String id) {
        return getSchedules().stream()
                .filter(s -> s.getId().equals(id))
                .findFirst();
    }

    /**
     * Find all schedules targeting a specific goal or task.
     */
    public List<ScheduleEntry> findSchedulesForTarget(String targetId) {
        return getSchedules().stream()
                .filter(s -> s.getTargetId().equals(targetId))
                .toList();
    }

    /**
     * Delete a schedule by ID.
     *
     * @throws IllegalArgumentException
     *             if not found
     */
    public void deleteSchedule(String id) {
        List<ScheduleEntry> schedules = getSchedules();
        boolean removed = schedules.removeIf(s -> s.getId().equals(id));
        if (!removed) {
            throw new IllegalArgumentException("Schedule not found: " + id);
        }
        saveSchedules(schedules);
        log.info("[Schedule] Deleted schedule: {}", id);
    }

    /**
     * Get schedules that are due for execution (enabled, not exhausted, next
     * execution at or before now).
     */
    public List<ScheduleEntry> getDueSchedules() {
        Instant now = clock.instant();
        return getSchedules().stream()
                .filter(ScheduleEntry::isEnabled)
                .filter(s -> !s.isExhausted())
                .filter(s -> s.getNextExecutionAt() != null && !s.getNextExecutionAt().isAfter(now))
                .toList();
    }

    /**
     * Record an execution: increment count, update timestamps, recompute next
     * execution. Disables exhausted schedules.
     */
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
            Instant nextExecution = computeNextExecution(entry.getCronExpression(), now);
            entry.setNextExecutionAt(nextExecution);
        }

        saveSchedules(getSchedules());
    }

    /**
     * Normalize a cron expression: converts 5-field (minute-level) to 6-field
     * (Spring format with seconds). Validates the result.
     *
     * @throws IllegalArgumentException
     *             if the cron expression is invalid
     */
    String normalizeCronExpression(String input) {
        return scheduleCronPort.normalize(input);
    }

    /**
     * Compute the next execution time after the given instant.
     *
     * @return next execution instant, or null if no future execution exists
     */
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
}
