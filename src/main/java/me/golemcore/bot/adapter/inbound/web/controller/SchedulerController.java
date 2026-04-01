package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.auto.ScheduleReportSender;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.model.ScheduleReportConfigUpdate;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final RuntimeConfigService runtimeConfigService;
    private final ChannelRegistry channelRegistry;

    @GetMapping
    public Mono<ResponseEntity<SchedulerStateResponse>> getState() {
        return Mono.just(ResponseEntity.ok(buildSchedulerStateResponse()));
    }

    @PostMapping("/schedules")
    public Mono<ResponseEntity<ScheduleDto>> createSchedule(@RequestBody CreateScheduleRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }

        try {
            ScheduleEntry.ScheduleType targetType = parseTargetType(request.targetType());
            String targetId = validateTargetId(request.targetId(), targetType);
            int maxExecutions = normalizeMaxExecutions(request.maxExecutions());
            ScheduleMode mode = parseMode(request.mode(), request.cronExpression());
            String cronExpression = buildRequestedCronExpression(
                    request.frequency(),
                    request.days(),
                    request.time(),
                    mode,
                    request.cronExpression());

            ScheduleReportConfig report = normalizeReportRequest(request.report());
            ScheduleEntry entry = scheduleService.createSchedule(
                    targetType,
                    targetId,
                    cronExpression,
                    maxExecutions,
                    Boolean.TRUE.equals(request.clearContextBeforeRun()),
                    report);
            String targetLabel = resolveTargetLabel(entry, autoModeService.getGoals());
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toScheduleDto(entry, targetLabel)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw badRequest(e.getMessage());
        }
    }

    @PutMapping("/schedules/{scheduleId}")
    public Mono<ResponseEntity<ScheduleDto>> updateSchedule(
            @PathVariable String scheduleId,
            @RequestBody UpdateScheduleRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }

        try {
            String normalizedScheduleId = requireId(scheduleId, "scheduleId");
            ScheduleEntry.ScheduleType targetType = parseTargetType(request.targetType());
            String targetId = validateTargetId(request.targetId(), targetType);
            int maxExecutions = normalizeMaxExecutions(request.maxExecutions());
            ScheduleMode mode = parseMode(request.mode(), request.cronExpression());
            String cronExpression = buildRequestedCronExpression(
                    request.frequency(),
                    request.days(),
                    request.time(),
                    mode,
                    request.cronExpression());

            ScheduleReportConfigUpdate reportUpdate = normalizeReportUpdateRequest(request.report());
            ScheduleEntry entry = scheduleService.updateSchedule(
                    normalizedScheduleId,
                    targetType,
                    targetId,
                    cronExpression,
                    maxExecutions,
                    normalizeEnabled(request.enabled()),
                    request.clearContextBeforeRun(),
                    reportUpdate);
            String targetLabel = resolveTargetLabel(entry, autoModeService.getGoals());
            return Mono.just(ResponseEntity.ok(toScheduleDto(entry, targetLabel)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public Mono<ResponseEntity<DeleteScheduleResponse>> deleteSchedule(@PathVariable String scheduleId) {
        requireFeatureEnabled();

        try {
            String normalizedScheduleId = requireId(scheduleId, "scheduleId");
            scheduleService.deleteSchedule(normalizedScheduleId);
            return Mono.just(ResponseEntity.ok(new DeleteScheduleResponse(normalizedScheduleId)));
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private SchedulerStateResponse buildSchedulerStateResponse() {
        List<Goal> allGoals = autoModeService.getGoals();
        Map<String, Goal> goalById = new HashMap<>();
        Map<String, String> taskTitleById = new HashMap<>();

        for (Goal goal : allGoals) {
            goalById.put(goal.getId(), goal);
        }

        List<GoalDto> goals = allGoals.stream()
                .filter(goal -> !autoModeService.isInboxGoal(goal))
                .sorted(Comparator.comparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(goal -> toGoalDto(goal, goalById, taskTitleById))
                .toList();

        List<TaskDto> standaloneTasks = allGoals.stream()
                .filter(autoModeService::isInboxGoal)
                .flatMap(goal -> goal.getTasks().stream())
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> {
                    taskTitleById.put(task.getId(), task.getTitle());
                    return toTaskDto(task, true);
                })
                .toList();

        List<ScheduleDto> schedules = scheduleService.getSchedules().stream()
                .sorted(Comparator
                        .comparing(ScheduleEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .map(entry -> toScheduleDto(entry, goalById, taskTitleById))
                .toList();

        return new SchedulerStateResponse(
                autoModeService.isFeatureEnabled(),
                autoModeService.isAutoModeEnabled(),
                goals,
                standaloneTasks,
                schedules,
                buildReportChannelOptions());
    }

    private void requireFeatureEnabled() {
        if (!autoModeService.isFeatureEnabled()) {
            throw badRequest(FEATURE_DISABLED);
        }
    }

    private static GoalDto toGoalDto(Goal goal, Map<String, Goal> goalById, Map<String, String> taskTitleById) {
        goalById.put(goal.getId(), goal);

        List<TaskDto> tasks = goal.getTasks().stream()
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> {
                    taskTitleById.put(task.getId(), task.getTitle());
                    return toTaskDto(task, false);
                })
                .toList();

        return new GoalDto(
                goal.getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getPrompt(),
                goal.getStatus().name(),
                goal.getCompletedTaskCount(),
                goal.getTasks().size(),
                tasks);
    }

    private static TaskDto toTaskDto(AutoTask task, boolean standalone) {
        String goalId = standalone ? null : task.getGoalId();
        return new TaskDto(
                task.getId(),
                goalId,
                task.getTitle(),
                task.getDescription(),
                task.getPrompt(),
                task.getStatus().name(),
                task.getOrder(),
                standalone);
    }

    private static ScheduleDto toScheduleDto(
            ScheduleEntry entry,
            Map<String, Goal> goalById,
            Map<String, String> taskTitleById) {
        String targetLabel = resolveTargetLabel(entry, goalById, taskTitleById);
        return toScheduleDto(entry, targetLabel);
    }

    private static ScheduleDto toScheduleDto(ScheduleEntry entry, String targetLabel) {
        ScheduleReportDto report = toScheduleReportDto(resolveReport(entry));
        return new ScheduleDto(
                entry.getId(),
                entry.getType().name(),
                entry.getTargetId(),
                targetLabel,
                entry.getCronExpression(),
                entry.isEnabled(),
                entry.isClearContextBeforeRun(),
                report,
                entry.getMaxExecutions(),
                entry.getExecutionCount(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getLastExecutedAt(),
                entry.getNextExecutionAt());
    }

    private List<ScheduleReportChannelOptionDto> buildReportChannelOptions() {
        String telegramSuggestedChatId = resolveTelegramSuggestedChatId();
        return channelRegistry.getAll().stream()
                .map(ChannelPort::getChannelType)
                .filter(type -> !StringValueSupport.isBlank(type))
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .filter(type -> !"web".equals(type))
                .distinct()
                .sorted((left, right) -> compareChannelTypes(left, right))
                .map(type -> new ScheduleReportChannelOptionDto(
                        type,
                        toChannelLabel(type),
                        "telegram".equals(type) ? telegramSuggestedChatId : null))
                .toList();
    }

    private String resolveTelegramSuggestedChatId() {
        RuntimeConfig runtimeConfig = runtimeConfigService.getRuntimeConfig();
        if (runtimeConfig == null || runtimeConfig.getTelegram() == null) {
            return null;
        }
        List<String> allowedUsers = runtimeConfig.getTelegram().getAllowedUsers();
        if (allowedUsers == null) {
            return null;
        }
        return allowedUsers.stream()
                .filter(value -> !StringValueSupport.isBlank(value))
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private static int compareChannelTypes(String left, String right) {
        if ("telegram".equals(left) && !"telegram".equals(right)) {
            return -1;
        }
        if (!"telegram".equals(left) && "telegram".equals(right)) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static String toChannelLabel(String channelType) {
        String[] segments = channelType.split("[-_\\s]+");
        StringBuilder label = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(segment.substring(0, 1).toUpperCase(Locale.ROOT));
            label.append(segment.substring(1));
        }
        return label.length() > 0 ? label.toString() : channelType;
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

        return goals.stream()
                .flatMap(goal -> goal.getTasks().stream())
                .filter(task -> task.getId().equals(entry.getTargetId()))
                .map(AutoTask::getTitle)
                .findFirst()
                .orElse(entry.getTargetId());
    }

    private String validateTargetId(String targetId, ScheduleEntry.ScheduleType type) {
        String normalizedTargetId = requireId(targetId, "targetId");

        if (type == ScheduleEntry.ScheduleType.GOAL) {
            if (autoModeService.getGoal(normalizedTargetId).isEmpty()) {
                throw badRequest("Goal not found: " + normalizedTargetId);
            }
        } else if (autoModeService.findGoalForTask(normalizedTargetId).isEmpty()) {
            throw badRequest("Task not found: " + normalizedTargetId);
        }

        return normalizedTargetId;
    }

    private static ScheduleEntry.ScheduleType parseTargetType(String value) {
        if (StringValueSupport.isBlank(value)) {
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

    private static Frequency parseFrequency(String value) {
        if (StringValueSupport.isBlank(value)) {
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

    private static ScheduleMode parseMode(String value, String cronExpression) {
        if (!StringValueSupport.isBlank(value)) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("simple".equals(normalized)) {
                return ScheduleMode.SIMPLE;
            }
            if ("advanced".equals(normalized)) {
                return ScheduleMode.ADVANCED;
            }
            throw badRequest("Unsupported mode: " + value);
        }

        if (!StringValueSupport.isBlank(cronExpression)) {
            return ScheduleMode.ADVANCED;
        }
        return ScheduleMode.SIMPLE;
    }

    private static String buildRequestedCronExpression(
            String frequencyValue,
            List<Integer> daysValue,
            String timeValue,
            ScheduleMode mode,
            String cronExpressionValue) {
        if (mode == ScheduleMode.ADVANCED) {
            if (StringValueSupport.isBlank(cronExpressionValue)) {
                throw badRequest("cronExpression is required for advanced mode");
            }
            return cronExpressionValue.trim();
        }

        Frequency frequency = parseFrequency(frequencyValue);
        Set<Integer> days = normalizeDays(daysValue, frequency);
        TimeValue time = parseTimeValue(timeValue);
        return buildCronExpression(frequency, days, time);
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
        if (StringValueSupport.isBlank(value)) {
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

    private static boolean normalizeEnabled(Boolean value) {
        if (value == null) {
            return true;
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

    private static String requireId(String value, String fieldName) {
        if (StringValueSupport.isBlank(value)) {
            throw badRequest(fieldName + " is required");
        }
        return value.trim();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private ScheduleReportConfig normalizeReportRequest(ScheduleReportRequest request) {
        if (request == null) {
            return null;
        }

        String channelType = normalizeOptionalString(request.channelType());
        String chatId = normalizeOptionalString(request.chatId());
        String webhookUrl = normalizeOptionalString(request.webhookUrl());
        String webhookBearerToken = normalizeOptionalString(request.webhookBearerToken());
        if (channelType == null && chatId == null && webhookUrl == null && webhookBearerToken == null) {
            return null;
        }

        ScheduleReportConfig report = ScheduleReportConfig.builder()
                .channelType(channelType)
                .chatId(chatId)
                .webhookUrl(webhookUrl)
                .webhookBearerToken(webhookBearerToken)
                .build();
        validateReportChannel(report);
        return report;
    }

    private ScheduleReportConfigUpdate normalizeReportUpdateRequest(ScheduleReportPatchRequest request) {
        if (request == null) {
            return ScheduleReportConfigUpdate.noChange();
        }
        if (StringValueSupport.isBlank(request.operation())) {
            throw badRequest("report.operation is required");
        }

        String operation = request.operation().trim().toUpperCase(Locale.ROOT);
        return switch (operation) {
        case "CLEAR" -> ScheduleReportConfigUpdate.clear();
        case "SET" -> {
            ScheduleReportConfig report = normalizeReportRequest(request.config());
            if (report == null) {
                throw badRequest("report.config is required when report.operation is SET");
            }
            yield ScheduleReportConfigUpdate.set(report);
        }
        default -> throw badRequest("Unsupported report.operation: " + request.operation());
        };
    }

    private static ScheduleReportDto toScheduleReportDto(ScheduleReportConfig report) {
        if (report == null) {
            return null;
        }
        return new ScheduleReportDto(
                report.getChannelType(),
                report.getChatId(),
                report.getWebhookUrl(),
                report.getWebhookBearerToken());
    }

    private static ScheduleReportConfig resolveReport(ScheduleEntry entry) {
        return entry.getReport();
    }

    private enum Frequency {
        DAILY, WEEKDAYS, WEEKLY, CUSTOM
    }

    private enum ScheduleMode {
        SIMPLE, ADVANCED
    }

    private record TimeValue(int hour, int minute) {
    }

    public record CreateScheduleRequest(
            String targetType,
            String targetId,
            String frequency,
            List<Integer> days,
            String time,
            Integer maxExecutions,
            String mode,
            String cronExpression,
            Boolean clearContextBeforeRun,
            ScheduleReportRequest report) {

        public CreateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions) {
            this(targetType, targetId, frequency, days, time, maxExecutions,
                    null, null, null, null);
        }

        public CreateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression, null, null);
        }

        public CreateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression,
                Boolean clearContextBeforeRun) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression,
                    clearContextBeforeRun, null);
        }

    }

    public record UpdateScheduleRequest(
            String targetType,
            String targetId,
            String frequency,
            List<Integer> days,
            String time,
            Integer maxExecutions,
            String mode,
            String cronExpression,
            Boolean enabled,
            Boolean clearContextBeforeRun,
            ScheduleReportPatchRequest report) {

        public UpdateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression,
                Boolean enabled) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression, enabled, null,
                    null);
        }

        public UpdateScheduleRequest(
                String targetType,
                String targetId,
                String frequency,
                List<Integer> days,
                String time,
                Integer maxExecutions,
                String mode,
                String cronExpression,
                Boolean enabled,
                Boolean clearContextBeforeRun) {
            this(targetType, targetId, frequency, days, time, maxExecutions, mode, cronExpression, enabled,
                    clearContextBeforeRun, null);
        }

    }

    public record SchedulerStateResponse(
            boolean featureEnabled,
            boolean autoModeEnabled,
            List<GoalDto> goals,
            List<TaskDto> standaloneTasks,
            List<ScheduleDto> schedules,
            List<ScheduleReportChannelOptionDto> reportChannelOptions) {
    }

    public record GoalDto(
            String id,
            String title,
            String description,
            String prompt,
            String status,
            long completedTasks,
            int totalTasks,
            List<TaskDto> tasks) {
    }

    public record TaskDto(
            String id,
            String goalId,
            String title,
            String description,
            String prompt,
            String status,
            int order,
            boolean standalone) {
    }

    public record ScheduleDto(
            String id,
            String type,
            String targetId,
            String targetLabel,
            String cronExpression,
            boolean enabled,
            boolean clearContextBeforeRun,
            ScheduleReportDto report,
            int maxExecutions,
            int executionCount,
            Instant createdAt,
            Instant updatedAt,
            Instant lastExecutedAt,
            Instant nextExecutionAt) {
    }

    public record ScheduleReportDto(
            String channelType,
            String chatId,
            String webhookUrl,
            String webhookBearerToken) {
    }

    public record ScheduleReportRequest(
            String channelType,
            String chatId,
            String webhookUrl,
            String webhookBearerToken) {
    }

    public record ScheduleReportPatchRequest(
            String operation,
            ScheduleReportRequest config) {
    }

    public record ScheduleReportChannelOptionDto(
            String type,
            String label,
            String suggestedChatId) {
    }

    public record DeleteScheduleResponse(String scheduleId) {
    }

    private void validateReportChannel(ScheduleReportConfig report) {
        String channelType = report.getChannelType();
        String chatId = report.getChatId();
        String webhookUrl = report.getWebhookUrl();
        String webhookBearerToken = report.getWebhookBearerToken();

        if (channelType == null && chatId != null) {
            throw badRequest("reportChannelType is required when reportChatId is set");
        }
        if (channelType == null) {
            throw badRequest("reportChannelType is required when report settings are set");
        }
        if (ScheduleReportSender.WEBHOOK_CHANNEL_TYPE.equals(channelType)) {
            if (!StringValueSupport.isBlank(chatId)) {
                throw badRequest("reportChatId is not supported for webhook channel type");
            }
            if (StringValueSupport.isBlank(webhookUrl)) {
                throw badRequest("reportWebhookUrl is required for webhook channel type");
            }
            if (!webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
                throw badRequest("reportWebhookUrl must start with http:// or https://");
            }
            return;
        }

        if (webhookUrl != null || webhookBearerToken != null) {
            throw badRequest("Webhook settings are only supported for webhook channel type");
        }
        if (channelRegistry.get(channelType).isEmpty()) {
            throw badRequest("Unknown channel type: " + channelType);
        }
    }

    private static String normalizeOptionalString(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }
}
