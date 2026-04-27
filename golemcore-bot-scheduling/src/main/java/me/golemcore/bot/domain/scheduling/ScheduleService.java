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

import me.golemcore.bot.domain.service.StringValueSupport;
import java.time.Clock;
import java.time.Duration;
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
 * Domain service for managing cron-based schedules for autonomous goal/task execution.
 */
@Slf4j
public class ScheduleService {

    private static final List<Duration> RETRY_BACKOFFS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private final SchedulePersistencePort schedulePersistencePort;
    private final ScheduleCronPort scheduleCronPort;
    private final Clock clock;
    private final ScheduleMigrationPort scheduleMigrationPort;

    private volatile List<ScheduleEntry> schedulesCache;

    public ScheduleService(SchedulePersistencePort schedulePersistencePort, ScheduleCronPort scheduleCronPort,
            Clock clock) {
        this(schedulePersistencePort, scheduleCronPort, clock, null);
    }

    public ScheduleService(SchedulePersistencePort schedulePersistencePort, ScheduleCronPort scheduleCronPort,
            Clock clock, ScheduleMigrationPort scheduleMigrationPort) {
        this.schedulePersistencePort = schedulePersistencePort;
        this.scheduleCronPort = scheduleCronPort;
        this.clock = clock;
        this.scheduleMigrationPort = scheduleMigrationPort;
    }

    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId, String cronExpression,
            int maxExecutions) {
        return createSchedule(type, targetId, cronExpression, maxExecutions, false);
    }

    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId, String cronExpression,
            int maxExecutions, boolean clearContextBeforeRun) {
        return createSchedule(type, targetId, cronExpression, maxExecutions, clearContextBeforeRun, null);
    }

    public synchronized ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean clearContextBeforeRun, ScheduleReportConfig report) {
        String normalizedCron = normalizeCronExpression(cronExpression);
        Instant now = clock.instant();
        Instant nextExecution = computeNextExecution(normalizedCron, now);

        ScheduleEntry entry = ScheduleEntry.builder()
                .id("sched-" + type.name().toLowerCase(Locale.ROOT) + "-"
                        + UUID.randomUUID().toString().substring(0, 8))
                .type(type).targetId(targetId).cronExpression(normalizedCron).enabled(true)
                .clearContextBeforeRun(clearContextBeforeRun).report(copyReport(report)).maxExecutions(maxExecutions)
                .executionCount(0).createdAt(now).updatedAt(now).nextExecutionAt(nextExecution).build();

        List<ScheduleEntry> schedules = new ArrayList<>(getSchedules());
        schedules.add(entry);
        saveSchedules(schedules);
        log.info("[Schedule] Created {} schedule for target {}: {}", type, targetId, normalizedCron);
        return entry;
    }

    public synchronized ScheduleEntry updateSchedule(String id, ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean enabled) {
        return updateSchedule(id, type, targetId, cronExpression, maxExecutions, enabled, null);
    }

    public synchronized ScheduleEntry updateSchedule(String id, ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean enabled, Boolean clearContextBeforeRun) {
        return updateSchedule(id, type, targetId, cronExpression, maxExecutions, enabled, clearContextBeforeRun,
                ScheduleReportConfigUpdate.noChange());
    }

    public synchronized ScheduleEntry updateSchedule(String id, ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions, boolean enabled, Boolean clearContextBeforeRun,
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
        entry.setRetryCount(0);
        entry.setActiveWindowStartedAt(null);
        entry.setNextWindowAt(null);
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
            if (scheduleMigrationPort != null) {
                scheduleMigrationPort.migrateIfNeeded();
            }
            schedulesCache = schedulePersistencePort.loadSchedules();
            schedulesCache.forEach(this::normalizeLoadedSchedule);
        }
        return List.copyOf(schedulesCache);
    }

    public synchronized void replaceSchedules(List<ScheduleEntry> schedules) {
        List<ScheduleEntry> normalizedSchedules = schedules != null ? new ArrayList<>(schedules) : new ArrayList<>();
        normalizedSchedules.forEach(this::normalizeLoadedSchedule);
        saveSchedules(normalizedSchedules);
    }

    public synchronized Optional<ScheduleEntry> findSchedule(String id) {
        return getSchedules().stream().filter(schedule -> schedule.getId().equals(id)).findFirst();
    }

    public synchronized List<ScheduleEntry> findSchedulesForTarget(String targetId) {
        return getSchedules().stream().filter(schedule -> schedule.getTargetId().equals(targetId)).toList();
    }

    public synchronized boolean isScheduledTaskBlocked(String scheduledTaskId) {
        if (StringValueSupport.isBlank(scheduledTaskId)) {
            return false;
        }
        String normalizedTargetId = scheduledTaskId.trim();
        return getSchedules().stream().filter(ScheduleEntry::isEnabled).filter(schedule -> !schedule.isExhausted())
                .filter(schedule -> schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .filter(schedule -> normalizedTargetId.equals(schedule.getTargetId()))
                .anyMatch(schedule -> schedule.getActiveWindowStartedAt() != null);
    }

    public synchronized void deleteSchedule(String id) {
        List<ScheduleEntry> schedules = new ArrayList<>(getSchedules());
        boolean removed = schedules.removeIf(schedule -> schedule.getId().equals(id));
        if (!removed) {
            throw new IllegalArgumentException("Schedule not found: " + id);
        }
        saveSchedules(schedules);
        log.info("[Schedule] Deleted schedule: {}", id);
    }

    public synchronized List<ScheduleEntry> getDueSchedules() {
        Instant now = clock.instant();
        List<ScheduleEntry> schedules = getSchedules();
        boolean changed = false;
        for (ScheduleEntry schedule : schedules) {
            changed |= normalizeExpiredRetryWindow(schedule, now);
        }
        if (changed) {
            saveSchedules(schedules);
        }
        return schedules.stream().filter(ScheduleEntry::isEnabled).filter(schedule -> !schedule.isExhausted()).filter(
                schedule -> schedule.getNextExecutionAt() != null && !schedule.getNextExecutionAt().isAfter(now))
                .filter(schedule -> !isBlockedByAnotherScheduledTaskWindow(schedule, schedules)).toList();
    }

    public synchronized void recordExecution(String id) {
        ScheduleEntry entry = findSchedule(id)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));
        Instant now = clock.instant();
        Instant completedWindowStartedAt = resolveActiveWindowStartedAt(entry, now);
        Instant nextExecutionAt = coalescePastNextExecution(entry.getCronExpression(),
                computeNextExecution(entry.getCronExpression(), completedWindowStartedAt), now);
        finalizeWindow(entry, now, nextExecutionAt);
        saveSchedules(getSchedules());
    }

    public synchronized void recordFailedAttempt(String id) {
        ScheduleEntry entry = findSchedule(id)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));

        Instant now = clock.instant();
        Instant windowStartedAt = resolveActiveWindowStartedAt(entry, now);
        Instant nextWindowAt = resolveNextWindowAt(entry, windowStartedAt);
        int retryCount = Math.max(0, entry.getRetryCount());

        if (retryCount < RETRY_BACKOFFS.size()) {
            Instant retryAt = now.plus(RETRY_BACKOFFS.get(retryCount));
            if (retryAt.isBefore(nextWindowAt)) {
                entry.setRetryCount(retryCount + 1);
                entry.setActiveWindowStartedAt(windowStartedAt);
                entry.setNextWindowAt(nextWindowAt);
                entry.setNextExecutionAt(retryAt);
                entry.setUpdatedAt(now);
                saveSchedules(getSchedules());
                log.warn("[Schedule] Retry {} for schedule {} scheduled at {}", retryCount + 1, id, retryAt);
                return;
            }
        }

        finalizeWindow(entry, now, coalescePastNextExecution(entry.getCronExpression(), nextWindowAt, now));
        saveSchedules(getSchedules());
    }

    public synchronized void disableSchedule(String id) {
        ScheduleEntry entry = findSchedule(id)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));

        Instant now = clock.instant();
        entry.setEnabled(false);
        entry.setNextExecutionAt(null);
        entry.setRetryCount(0);
        entry.setActiveWindowStartedAt(null);
        entry.setNextWindowAt(null);
        entry.setUpdatedAt(now);
        saveSchedules(getSchedules());
        log.warn("[Schedule] Disabled schedule {}", id);
    }

    private void finalizeWindow(ScheduleEntry entry, Instant now, Instant nextExecutionAt) {
        entry.setExecutionCount(entry.getExecutionCount() + 1);
        entry.setLastExecutedAt(now);
        entry.setUpdatedAt(now);
        entry.setRetryCount(0);
        entry.setActiveWindowStartedAt(null);
        entry.setNextWindowAt(null);

        if (entry.isExhausted()) {
            entry.setEnabled(false);
            entry.setNextExecutionAt(null);
            log.info("[Schedule] Schedule {} exhausted after {} executions", entry.getId(), entry.getExecutionCount());
        } else {
            entry.setNextExecutionAt(nextExecutionAt);
        }
    }

    String normalizeCronExpression(String input) {
        return scheduleCronPort.normalize(input);
    }

    private Instant resolveActiveWindowStartedAt(ScheduleEntry entry, Instant now) {
        if (entry.getActiveWindowStartedAt() != null) {
            return entry.getActiveWindowStartedAt();
        }
        if (entry.getNextExecutionAt() != null && !entry.getNextExecutionAt().isAfter(now)) {
            return entry.getNextExecutionAt();
        }
        return now;
    }

    private Instant resolveNextWindowAt(ScheduleEntry entry, Instant windowStartedAt) {
        if (entry.getNextWindowAt() != null) {
            return entry.getNextWindowAt();
        }
        return computeNextExecution(entry.getCronExpression(), windowStartedAt);
    }

    private Instant coalescePastNextExecution(String cronExpression, Instant candidate, Instant now) {
        if (candidate != null && candidate.isBefore(now)) {
            return computeNextExecution(cronExpression, now);
        }
        return candidate;
    }

    private boolean normalizeExpiredRetryWindow(ScheduleEntry entry, Instant now) {
        if (entry.getRetryCount() <= 0 || entry.getNextWindowAt() == null || entry.getCronExpression() == null) {
            return false;
        }
        if (entry.getNextWindowAt().isAfter(now)) {
            return false;
        }
        Instant promotedWindow = entry.getNextWindowAt();
        entry.setRetryCount(0);
        entry.setActiveWindowStartedAt(promotedWindow);
        entry.setNextWindowAt(computeNextExecution(entry.getCronExpression(), promotedWindow));
        entry.setNextExecutionAt(promotedWindow);
        entry.setUpdatedAt(now);
        return true;
    }

    private boolean isBlockedByAnotherScheduledTaskWindow(ScheduleEntry candidate, List<ScheduleEntry> schedules) {
        if (candidate.getType() != ScheduleEntry.ScheduleType.SCHEDULED_TASK
                || StringValueSupport.isBlank(candidate.getTargetId())) {
            return false;
        }
        return schedules.stream().filter(ScheduleEntry::isEnabled).filter(schedule -> !schedule.isExhausted())
                .filter(schedule -> schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .filter(schedule -> candidate.getTargetId().equals(schedule.getTargetId()))
                .filter(schedule -> schedule.getActiveWindowStartedAt() != null).min((left, right) -> {
                    int byWindow = left.getActiveWindowStartedAt().compareTo(right.getActiveWindowStartedAt());
                    return byWindow != 0 ? byWindow : left.getId().compareTo(right.getId());
                }).filter(owner -> !candidate.getId().equals(owner.getId())).isPresent();
    }

    Instant computeNextExecution(String cronExpression, Instant after) {
        return scheduleCronPort.nextExecution(cronExpression, after);
    }

    private void saveSchedules(List<ScheduleEntry> schedules) {
        List<ScheduleEntry> persistedSchedules = new ArrayList<>(schedules);
        schedulePersistencePort.saveSchedules(persistedSchedules);
        schedulesCache = persistedSchedules;
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
        return ScheduleReportConfig.builder().channelType(report.getChannelType()).chatId(report.getChatId())
                .webhookUrl(report.getWebhookUrl()).webhookBearerToken(report.getWebhookBearerToken()).build();
    }
}
