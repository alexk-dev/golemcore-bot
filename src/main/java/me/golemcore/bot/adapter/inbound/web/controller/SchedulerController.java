package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scheduler management endpoints for dashboard UI.
 */
@RestController
@RequestMapping("/api/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private static final String FEATURE_DISABLED = "Auto mode feature is disabled";
    private static final Set<Integer> WEEKDAY_SET = Set.of(1, 2, 3, 4, 5, 6, 7);

    private final AutoModeService autoModeService;
    private final ScheduleService scheduleService;

    @GetMapping
    public Mono<ResponseEntity<SchedulerStateResponse>> getState() {
        List<Goal> goals = autoModeService.getGoals();
        Map<String, Goal> goalById = new HashMap<>();
        Map<String, String> taskTitleById = new HashMap<>();

        List<GoalDto> goalDtos = goals.stream()
                .sorted(Comparator.comparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(goal -> toGoalDto(goal, goalById, taskTitleById))
                .toList();

        List<ScheduleDto> schedules = scheduleService.getSchedules().stream()
                .sorted(Comparator
                        .comparing(ScheduleEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .map(entry -> toScheduleDto(entry, goalById, taskTitleById))
                .toList();

        SchedulerStateResponse response = new SchedulerStateResponse(
                autoModeService.isFeatureEnabled(),
                autoModeService.isAutoModeEnabled(),
                goalDtos,
                schedules);
        return Mono.just(ResponseEntity.ok(response));
    }

    @PostMapping("/schedules")
    public Mono<ResponseEntity<ScheduleDto>> createSchedule(@RequestBody CreateScheduleRequest request) {
        requireFeatureEnabled();

        if (request == null) {
            throw badRequest("Request body is required");
        }

        ScheduleEntry.ScheduleType targetType = parseTargetType(request.targetType());
        String targetId = validateTargetId(request.targetId(), targetType);
        Frequency frequency = parseFrequency(request.frequency());
        Set<Integer> days = normalizeDays(request.days(), frequency);
        TimeValue timeValue = parseTimeValue(request.time());
        int maxExecutions = normalizeMaxExecutions(request.maxExecutions());
        String cronExpression = buildCronExpression(frequency, days, timeValue);

        try {
            ScheduleEntry entry = scheduleService.createSchedule(targetType, targetId, cronExpression, maxExecutions);
            String targetLabel = resolveTargetLabel(entry, autoModeService.getGoals());
            ScheduleDto response = toScheduleDto(entry, targetLabel);
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(response));
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public Mono<ResponseEntity<DeleteScheduleResponse>> deleteSchedule(@PathVariable String scheduleId) {
        requireFeatureEnabled();

        if (scheduleId == null || scheduleId.isBlank()) {
            throw badRequest("scheduleId is required");
        }

        try {
            scheduleService.deleteSchedule(scheduleId);
            return Mono.just(ResponseEntity.ok(new DeleteScheduleResponse(scheduleId)));
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private static GoalDto toGoalDto(Goal goal, Map<String, Goal> goalById, Map<String, String> taskTitleById) {
        goalById.put(goal.getId(), goal);

        List<TaskDto> tasks = goal.getTasks().stream()
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> {
                    taskTitleById.put(task.getId(), task.getTitle());
                    return new TaskDto(task.getId(), task.getTitle(), task.getStatus().name());
                })
                .toList();

        return new GoalDto(goal.getId(), goal.getTitle(), goal.getStatus().name(), tasks);
    }

    private static ScheduleDto toScheduleDto(
            ScheduleEntry entry,
            Map<String, Goal> goalById,
            Map<String, String> taskTitleById) {
        String targetLabel = resolveTargetLabel(entry, goalById, taskTitleById);
        return toScheduleDto(entry, targetLabel);
    }

    private static ScheduleDto toScheduleDto(ScheduleEntry entry, String targetLabel) {
        return new ScheduleDto(
                entry.getId(),
                entry.getType().name(),
                entry.getTargetId(),
                targetLabel,
                entry.getCronExpression(),
                entry.isEnabled(),
                entry.getMaxExecutions(),
                entry.getExecutionCount(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getLastExecutedAt(),
                entry.getNextExecutionAt());
    }

    private static String resolveTargetLabel(
            ScheduleEntry entry,
            Map<String, Goal> goalById,
            Map<String, String> taskTitleById) {
        if (entry.getType() == ScheduleEntry.ScheduleType.GOAL) {
            Goal goal = goalById.get(entry.getTargetId());
            return goal != null ? goal.getTitle() : entry.getTargetId();
        }
        String taskTitle = taskTitleById.get(entry.getTargetId());
        return taskTitle != null ? taskTitle : entry.getTargetId();
    }

    private String resolveTargetLabel(ScheduleEntry entry, List<Goal> goals) {
        if (entry.getType() == ScheduleEntry.ScheduleType.GOAL) {
            Optional<Goal> goal = goals.stream()
                    .filter(item -> item.getId().equals(entry.getTargetId()))
                    .findFirst();
            return goal.map(Goal::getTitle).orElse(entry.getTargetId());
        }

        for (Goal goal : goals) {
            Optional<AutoTask> task = goal.getTasks().stream()
                    .filter(item -> item.getId().equals(entry.getTargetId()))
                    .findFirst();
            if (task.isPresent()) {
                return task.get().getTitle();
            }
        }

        return entry.getTargetId();
    }

    private void requireFeatureEnabled() {
        if (!autoModeService.isFeatureEnabled()) {
            throw badRequest(FEATURE_DISABLED);
        }
    }

    private static ScheduleEntry.ScheduleType parseTargetType(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("targetType is required");
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("GOAL".equals(normalized)) {
            return ScheduleEntry.ScheduleType.GOAL;
        }
        if ("TASK".equals(normalized)) {
            return ScheduleEntry.ScheduleType.TASK;
        }
        throw badRequest("Unsupported targetType: " + value);
    }

    private String validateTargetId(String targetId, ScheduleEntry.ScheduleType type) {
        if (targetId == null || targetId.isBlank()) {
            throw badRequest("targetId is required");
        }

        String normalizedTargetId = targetId.trim();
        if (type == ScheduleEntry.ScheduleType.GOAL) {
            if (autoModeService.getGoal(normalizedTargetId).isEmpty()) {
                throw badRequest("Goal not found: " + normalizedTargetId);
            }
        } else {
            if (autoModeService.findGoalForTask(normalizedTargetId).isEmpty()) {
                throw badRequest("Task not found: " + normalizedTargetId);
            }
        }

        return normalizedTargetId;
    }

    private static Frequency parseFrequency(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("frequency is required");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
        case "daily" -> Frequency.DAILY;
        case "weekdays" -> Frequency.WEEKDAYS;
        case "weekly" -> Frequency.WEEKLY;
        case "custom" -> Frequency.CUSTOM;
        default -> throw badRequest("Unsupported frequency: " + value);
        };
    }

    private static Set<Integer> normalizeDays(List<Integer> requestedDays, Frequency frequency) {
        if (frequency == Frequency.DAILY || frequency == Frequency.WEEKDAYS) {
            return Set.of();
        }

        if (requestedDays == null || requestedDays.isEmpty()) {
            throw badRequest("At least one weekday is required for weekly/custom frequency");
        }

        Set<Integer> normalizedDays = new HashSet<>();
        for (Integer day : requestedDays) {
            if (day == null || !WEEKDAY_SET.contains(day)) {
                throw badRequest("Weekday must be between 1 and 7");
            }
            normalizedDays.add(day);
        }

        if (normalizedDays.isEmpty()) {
            throw badRequest("At least one weekday is required for weekly/custom frequency");
        }

        return normalizedDays;
    }

    private static TimeValue parseTimeValue(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("time is required");
        }

        String normalized = value.trim();
        if (normalized.matches("\\d{4}")) {
            int hour = Integer.parseInt(normalized.substring(0, 2));
            int minute = Integer.parseInt(normalized.substring(2, 4));
            return toTimeValue(hour, minute);
        }

        String[] parts = normalized.split(":");
        if (parts.length != 2) {
            throw badRequest("Invalid time format. Use HH:mm or HHmm");
        }

        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return toTimeValue(hour, minute);
        } catch (NumberFormatException e) {
            throw badRequest("Invalid time format. Use HH:mm or HHmm");
        }
    }

    private static TimeValue toTimeValue(int hour, int minute) {
        boolean validHour = hour >= 0 && hour <= 23;
        boolean validMinute = minute >= 0 && minute <= 59;
        if (!validHour || !validMinute) {
            throw badRequest("Invalid time value. Hour must be 0..23 and minute 0..59");
        }
        return new TimeValue(hour, minute);
    }

    private static int normalizeMaxExecutions(Integer value) {
        if (value == null) {
            return -1;
        }

        if (value < 0) {
            throw badRequest("maxExecutions must be >= 0");
        }

        if (value == 0) {
            return -1;
        }

        return value;
    }

    private static String buildCronExpression(Frequency frequency, Set<Integer> days, TimeValue timeValue) {
        if (frequency == Frequency.DAILY) {
            return String.format(Locale.ROOT, "0 %d %d * * *", timeValue.minute(), timeValue.hour());
        }

        if (frequency == Frequency.WEEKDAYS) {
            return String.format(Locale.ROOT, "0 %d %d * * MON-FRI", timeValue.minute(), timeValue.hour());
        }

        List<Integer> sortedDays = new ArrayList<>(days);
        sortedDays.sort(Integer::compareTo);
        String joinedDays = sortedDays.stream()
                .map(SchedulerController::weekdayToCron)
                .reduce((left, right) -> left + "," + right)
                .orElse("MON");

        return String.format(Locale.ROOT, "0 %d %d * * %s", timeValue.minute(), timeValue.hour(), joinedDays);
    }

    private static String weekdayToCron(int weekday) {
        return switch (weekday) {
        case 1 -> "MON";
        case 2 -> "TUE";
        case 3 -> "WED";
        case 4 -> "THU";
        case 5 -> "FRI";
        case 6 -> "SAT";
        case 7 -> "SUN";
        default -> throw badRequest("Weekday must be between 1 and 7");
        };
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private enum Frequency {
        DAILY, WEEKDAYS, WEEKLY, CUSTOM
    }

    private record TimeValue(int hour, int minute) {
    }

    public record CreateScheduleRequest(
            String targetType,
            String targetId,
            String frequency,
            List<Integer> days,
            String time,
            Integer maxExecutions) {
    }

    public record SchedulerStateResponse(
            boolean featureEnabled,
            boolean autoModeEnabled,
            List<GoalDto> goals,
            List<ScheduleDto> schedules) {
    }

    public record GoalDto(String id, String title, String status, List<TaskDto> tasks) {
    }

    public record TaskDto(String id, String title, String status) {
    }

    public record ScheduleDto(
            String id,
            String type,
            String targetId,
            String targetLabel,
            String cronExpression,
            boolean enabled,
            int maxExecutions,
            int executionCount,
            Instant createdAt,
            Instant updatedAt,
            Instant lastExecutedAt,
            Instant nextExecutionAt) {
    }

    public record DeleteScheduleResponse(String scheduleId) {
    }
}
