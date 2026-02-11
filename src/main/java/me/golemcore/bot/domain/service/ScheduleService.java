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

import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service for managing cron-based schedules for autonomous goal/task
 * execution. Schedules are persisted in {@code auto/schedules.json} via
 * {@link StoragePort}.
 *
 * <p>
 * Only GOAL and TASK schedule types exist. Goals/tasks without explicit
 * schedules are never auto-processed.
 */
@Service
@Slf4j
public class ScheduleService {

    private static final String AUTO_DIR = "auto";
    private static final String SCHEDULES_FILE = "schedules.json";
    private static final int CRON_FIVE_FIELDS = 5;
    private static final int CRON_SIX_FIELDS = 6;
    private static final TypeReference<List<ScheduleEntry>> SCHEDULE_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile List<ScheduleEntry> schedulesCache;

    public ScheduleService(StoragePort storagePort, ObjectMapper objectMapper, Clock clock) {
        this.storagePort = storagePort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Create a new schedule entry. Validates the cron expression, computes the next
     * execution time, and persists.
     */
    public ScheduleEntry createSchedule(ScheduleEntry.ScheduleType type, String targetId,
            String cronExpression, int maxExecutions) {
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
     * Get all schedules (cached, loaded on first access).
     */
    public synchronized List<ScheduleEntry> getSchedules() {
        if (schedulesCache == null) {
            schedulesCache = loadSchedules();
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
    static String normalizeCronExpression(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Cron expression cannot be empty");
        }

        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+");

        String sixFieldCron;
        if (parts.length == CRON_FIVE_FIELDS) {
            sixFieldCron = "0 " + trimmed;
        } else if (parts.length == CRON_SIX_FIELDS) {
            sixFieldCron = trimmed;
        } else {
            throw new IllegalArgumentException("Invalid cron expression: expected 5 or 6 fields, got " + parts.length);
        }

        try {
            CronExpression.parse(sixFieldCron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression '" + trimmed + "': " + e.getMessage());
        }

        return sixFieldCron;
    }

    /**
     * Compute the next execution time after the given instant.
     *
     * @return next execution instant, or null if no future execution exists
     */
    Instant computeNextExecution(String cronExpression, Instant after) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime afterLocal = LocalDateTime.ofInstant(after, ZoneOffset.UTC);
        LocalDateTime next = cron.next(afterLocal);
        if (next == null) {
            return null;
        }
        return next.toInstant(ZoneOffset.UTC);
    }

    private void saveSchedules(List<ScheduleEntry> schedules) {
        try {
            String json = objectMapper.writeValueAsString(schedules);
            storagePort.putText(AUTO_DIR, SCHEDULES_FILE, json).join();
            schedulesCache = schedules;
        } catch (Exception e) { // NOSONAR - intentionally catch all for persistence fallback
            log.error("[Schedule] Failed to save schedules", e);
        }
    }

    private List<ScheduleEntry> loadSchedules() {
        try {
            String json = storagePort.getText(AUTO_DIR, SCHEDULES_FILE).join();
            if (json != null && !json.isBlank()) {
                return new ArrayList<>(objectMapper.readValue(json, SCHEDULE_LIST_TYPE_REF));
            }
        } catch (IOException | RuntimeException e) { // NOSONAR - intentionally catch all for fallback
            log.debug("[Schedule] No schedules found or failed to parse: {}", e.getMessage());
        }
        return new ArrayList<>();
    }
}
