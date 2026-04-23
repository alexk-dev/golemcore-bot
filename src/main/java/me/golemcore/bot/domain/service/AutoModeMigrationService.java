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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.SchedulePersistencePort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

/**
 * Performs one-time migration of legacy global goals/tasks to session-scoped
 * goals and persistent scheduled tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoModeMigrationService {

    private static final String AUTO_DIR = "auto";
    private static final String LEGACY_GOALS_FILE = "goals.json";
    private static final String MIGRATION_MARKER_FILE = "goals-session-migration.marker";
    private static final TypeReference<List<Goal>> GOAL_LIST_TYPE_REF = new TypeReference<>() {
    };

    private final StoragePort storagePort;
    private final ObjectMapper objectMapper;
    private final SessionPort sessionPort;
    private final SessionScopedGoalService sessionScopedGoalService;
    private final PersistentScheduledTaskService persistentScheduledTaskService;
    private final SchedulePersistencePort schedulePersistencePort;

    public synchronized void migrateIfNeeded() {
        try {
            Boolean alreadyMigrated = storagePort.exists(AUTO_DIR, MIGRATION_MARKER_FILE).join();
            if (Boolean.TRUE.equals(alreadyMigrated)) {
                return;
            }

            List<Goal> legacyGoals = loadLegacyGoals();
            List<ScheduleEntry> schedules = schedulePersistencePort.loadSchedules();

            migrateLegacyGoalsToLatestSession(legacyGoals);
            migrateScheduledTargets(legacyGoals, schedules);
            schedulePersistencePort.saveSchedules(schedules);
            storagePort.putText(AUTO_DIR, MIGRATION_MARKER_FILE, Instant.now().toString()).join();
            log.info("[AutoModeMigration] Legacy goals/tasks migration completed");
        } catch (RuntimeException exception) { // NOSONAR - startup must remain resilient
            log.warn("[AutoModeMigration] Migration skipped: {}", exception.getMessage());
        }
    }

    private List<Goal> loadLegacyGoals() {
        try {
            String json = storagePort.getText(AUTO_DIR, LEGACY_GOALS_FILE).join();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<Goal> goals = objectMapper.readValue(json, GOAL_LIST_TYPE_REF);
            return goals != null ? new ArrayList<>(goals) : new ArrayList<>();
        } catch (IOException | RuntimeException exception) { // NOSONAR
            log.debug("[AutoModeMigration] Failed to load legacy goals: {}", exception.getMessage());
            return new ArrayList<>();
        }
    }

    private void migrateLegacyGoalsToLatestSession(List<Goal> legacyGoals) {
        if (legacyGoals.isEmpty()) {
            return;
        }
        AgentSession latestSession = findLatestTelegramOrWebSession();
        if (latestSession == null || latestSession.getId() == null || latestSession.getId().isBlank()) {
            return;
        }

        List<Goal> migratedGoals = legacyGoals.stream()
                .map(this::deepCopyGoal)
                .peek(goal -> goal.setSessionId(latestSession.getId()))
                .peek(goal -> {
                    if ("inbox".equals(goal.getId())) {
                        goal.setSystemInbox(true);
                    }
                })
                .toList();
        sessionScopedGoalService.replaceGoals(latestSession.getId(), migratedGoals);
    }

    private void migrateScheduledTargets(List<Goal> legacyGoals, List<ScheduleEntry> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        LegacyTargetIndex targetIndex = buildLegacyTargetIndex(legacyGoals);
        for (ScheduleEntry schedule : schedules) {
            migrateScheduleTarget(schedule, targetIndex);
        }
    }

    private LegacyTargetIndex buildLegacyTargetIndex(List<Goal> legacyGoals) {
        Map<String, Goal> goalById = new HashMap<>();
        Map<String, AutoTask> taskById = new HashMap<>();
        Map<String, Goal> taskGoalById = new HashMap<>();
        for (Goal goal : legacyGoals) {
            indexLegacyGoal(goal, goalById, taskById, taskGoalById);
        }
        return new LegacyTargetIndex(goalById, taskById, taskGoalById);
    }

    private void indexLegacyGoal(Goal goal, Map<String, Goal> goalById, Map<String, AutoTask> taskById,
            Map<String, Goal> taskGoalById) {
        if (goal == null || goal.getId() == null) {
            return;
        }
        goalById.put(goal.getId(), goal);
        if (goal.getTasks() == null) {
            return;
        }
        for (AutoTask task : goal.getTasks()) {
            if (task == null || task.getId() == null) {
                continue;
            }
            taskById.put(task.getId(), task);
            taskGoalById.put(task.getId(), goal);
        }
    }

    private void migrateScheduleTarget(ScheduleEntry schedule, LegacyTargetIndex targetIndex) {
        if (schedule == null || schedule.getType() == null
                || schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
            return;
        }
        if (schedule.getType() == ScheduleEntry.ScheduleType.GOAL) {
            migrateGoalSchedule(schedule, targetIndex.goalById());
            return;
        }
        if (schedule.getType() == ScheduleEntry.ScheduleType.TASK) {
            migrateTaskSchedule(schedule, targetIndex);
        }
    }

    private void migrateGoalSchedule(ScheduleEntry schedule, Map<String, Goal> goalById) {
        Goal goal = goalById.get(schedule.getTargetId());
        if (goal == null) {
            return;
        }
        schedule.setType(ScheduleEntry.ScheduleType.SCHEDULED_TASK);
        schedule.setTargetId(persistentScheduledTaskService.createFromLegacyGoal(goal).getId());
    }

    private void migrateTaskSchedule(ScheduleEntry schedule, LegacyTargetIndex targetIndex) {
        AutoTask task = targetIndex.taskById().get(schedule.getTargetId());
        if (task == null) {
            return;
        }
        schedule.setType(ScheduleEntry.ScheduleType.SCHEDULED_TASK);
        schedule.setTargetId(persistentScheduledTaskService.createFromLegacyTask(
                targetIndex.taskGoalById().get(task.getId()),
                task).getId());
    }

    private AgentSession findLatestTelegramOrWebSession() {
        return sessionPort.listAll().stream()
                .filter(Objects::nonNull)
                .filter(session -> ChannelTypes.TELEGRAM.equals(session.getChannelType())
                        || ChannelTypes.WEB.equals(session.getChannelType()))
                .max(Comparator.comparing(this::resolveLastActivity, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private Instant resolveLastActivity(AgentSession session) {
        if (session == null) {
            return null;
        }
        return session.getUpdatedAt() != null ? session.getUpdatedAt() : session.getCreatedAt();
    }

    private Goal deepCopyGoal(Goal goal) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(goal), Goal.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clone goal during migration", exception);
        }
    }

    private record LegacyTargetIndex(
            Map<String, Goal> goalById,
            Map<String, AutoTask> taskById,
            Map<String, Goal> taskGoalById) {
    }
}
