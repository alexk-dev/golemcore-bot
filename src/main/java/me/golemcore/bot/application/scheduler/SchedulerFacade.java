package me.golemcore.bot.application.scheduler;

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
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.domain.model.ScheduleReportConfigUpdate;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;

public class SchedulerFacade {

    private static final String FEATURE_DISABLED = "Auto mode feature is disabled";
    private static final Set<Integer> WEEKDAY_SET = Set.of(1, 2, 3, 4, 5, 6, 7);

    private final AutoModeService autoModeService;
    private final ScheduleService scheduleService;
    private final RuntimeConfigService runtimeConfigService;
    private final ChannelRuntimePort channelRuntimePort;

    public SchedulerFacade(
            AutoModeService autoModeService,
            ScheduleService scheduleService,
            RuntimeConfigService runtimeConfigService,
            ChannelRuntimePort channelRuntimePort) {
        this.autoModeService = autoModeService;
        this.scheduleService = scheduleService;
        this.runtimeConfigService = runtimeConfigService;
        this.channelRuntimePort = channelRuntimePort;
    }

    public SchedulerStateView getState() {
        List<Goal> allGoals = autoModeService.getGoals();
        Map<String, Goal> goalById = new HashMap<>();
        Map<String, String> taskTitleById = new HashMap<>();
        Map<String, String> scheduledTaskTitleById = new HashMap<>();

        for (Goal goal : allGoals) {
            goalById.put(goal.getId(), goal);
        }

        List<GoalView> goals = allGoals.stream()
                .filter(goal -> !autoModeService.isInboxGoal(goal))
                .sorted(Comparator.comparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(goal -> toGoalView(goal, goalById, taskTitleById))
                .toList();

        List<TaskView> standaloneTasks = allGoals.stream()
                .filter(autoModeService::isInboxGoal)
                .flatMap(goal -> goal.getTasks().stream())
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> {
                    taskTitleById.put(task.getId(), task.getTitle());
                    return toTaskView(task, true);
                })
                .toList();

        List<ScheduledTaskView> scheduledTasks = autoModeService.getScheduledTasks().stream()
                .sorted(Comparator.comparing(ScheduledTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(task -> {
                    scheduledTaskTitleById.put(task.getId(), task.getTitle());
                    return toScheduledTaskView(task);
                })
                .toList();

        List<ScheduleView> schedules = scheduleService.getSchedules().stream()
                .sorted(Comparator
                        .comparing(ScheduleEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .map(entry -> toScheduleView(entry, goalById, taskTitleById, scheduledTaskTitleById))
                .toList();

        return new SchedulerStateView(
                autoModeService.isFeatureEnabled(),
                autoModeService.isAutoModeEnabled(),
                goals,
                standaloneTasks,
                scheduledTasks,
                schedules,
                buildReportChannelOptions());
    }

    public ScheduleView createSchedule(CreateScheduleCommand request) {
        requireFeatureEnabled();
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

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
        return toScheduleView(entry, resolveTargetLabel(entry, List.of()));
    }

    public ScheduleView updateSchedule(String scheduleId, UpdateScheduleCommand request) {
        requireFeatureEnabled();
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

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
        return toScheduleView(entry, resolveTargetLabel(entry, List.of()));
    }

    public void deleteSchedule(String scheduleId) {
        requireFeatureEnabled();
        String normalizedScheduleId = requireId(scheduleId, "scheduleId");
        scheduleService.deleteSchedule(normalizedScheduleId);
    }

    private void requireFeatureEnabled() {
        if (!autoModeService.isFeatureEnabled()) {
            throw new IllegalArgumentException(FEATURE_DISABLED);
        }
    }

    private GoalView toGoalView(Goal goal, Map<String, Goal> goalById, Map<String, String> taskTitleById) {
        goalById.put(goal.getId(), goal);

        List<TaskView> tasks = goal.getTasks().stream()
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> {
                    taskTitleById.put(task.getId(), task.getTitle());
                    return toTaskView(task, false);
                })
                .toList();

        return new GoalView(
                goal.getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getPrompt(),
                goal.getStatus().name(),
                goal.getCompletedTaskCount(),
                goal.getTasks().size(),
                tasks);
    }

    private ScheduledTaskView toScheduledTaskView(ScheduledTask task) {
        return new ScheduledTaskView(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getPrompt(),
                task.getReflectionModelTier(),
                task.isReflectionTierPriority(),
                task.getLegacySourceType(),
                task.getLegacySourceId());
    }

    private TaskView toTaskView(AutoTask task, boolean standalone) {
        String goalId = standalone ? null : task.getGoalId();
        return new TaskView(
                task.getId(),
                goalId,
                task.getTitle(),
                task.getDescription(),
                task.getPrompt(),
                task.getStatus().name(),
                task.getOrder(),
                standalone);
    }

    private ScheduleView toScheduleView(
            ScheduleEntry entry,
            Map<String, Goal> goalById,
            Map<String, String> taskTitleById,
            Map<String, String> scheduledTaskTitleById) {
        String targetLabel = resolveTargetLabel(entry, goalById, taskTitleById, scheduledTaskTitleById);
        return toScheduleView(entry, targetLabel);
    }

    private ScheduleView toScheduleView(ScheduleEntry entry, String targetLabel) {
        ScheduleReportView report = toScheduleReportView(entry.getReport());
        return new ScheduleView(
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

    private List<ScheduleReportChannelOptionView> buildReportChannelOptions() {
        String telegramSuggestedChatId = resolveTelegramSuggestedChatId();
        return channelRuntimePort.listChannels().stream()
                .map(ChannelDeliveryPort::getChannelType)
                .filter(type -> !StringValueSupport.isBlank(type))
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .filter(type -> !ChannelTypes.WEB.equals(type))
                .distinct()
                .sorted(SchedulerFacade::compareChannelTypes)
                .map(type -> new ScheduleReportChannelOptionView(
                        type,
                        toChannelLabel(type),
                        ChannelTypes.TELEGRAM.equals(type) ? telegramSuggestedChatId : null))
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
        if (ChannelTypes.TELEGRAM.equals(left) && !ChannelTypes.TELEGRAM.equals(right)) {
            return -1;
        }
        if (!ChannelTypes.TELEGRAM.equals(left) && ChannelTypes.TELEGRAM.equals(right)) {
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
        return !label.isEmpty() ? label.toString() : channelType;
    }

    private static String resolveTargetLabel(
            ScheduleEntry entry,
            Map<String, Goal> goalById,
            Map<String, String> taskTitleById,
            Map<String, String> scheduledTaskTitleById) {
        if (entry.getType() == ScheduleEntry.ScheduleType.GOAL) {
            Goal goal = goalById.get(entry.getTargetId());
            return goal != null ? goal.getTitle() : entry.getTargetId();
        }
        if (entry.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
            String scheduledTaskTitle = scheduledTaskTitleById.get(entry.getTargetId());
            return scheduledTaskTitle != null ? scheduledTaskTitle : entry.getTargetId();
        }
        String taskTitle = taskTitleById.get(entry.getTargetId());
        return taskTitle != null ? taskTitle : entry.getTargetId();
    }

    private String resolveTargetLabel(ScheduleEntry entry, List<Goal> goals) {
        if (entry.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
            return autoModeService.getScheduledTask(entry.getTargetId())
                    .map(ScheduledTask::getTitle)
                    .orElse(entry.getTargetId());
        }
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
            throw new IllegalArgumentException("Goal schedules are no longer supported");
        }
        if (type == ScheduleEntry.ScheduleType.TASK) {
            throw new IllegalArgumentException("Task schedules are no longer supported");
        }
        if (autoModeService.getScheduledTask(normalizedTargetId).isEmpty()) {
            throw new IllegalArgumentException("Scheduled task not found: " + normalizedTargetId);
        }

        return normalizedTargetId;
    }

    private static ScheduleEntry.ScheduleType parseTargetType(String value) {
        if (StringValueSupport.isBlank(value)) {
            throw new IllegalArgumentException("targetType is required");
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("GOAL".equals(normalized)) {
            return ScheduleEntry.ScheduleType.GOAL;
        }
        if ("TASK".equals(normalized)) {
            return ScheduleEntry.ScheduleType.TASK;
        }
        throw new IllegalArgumentException("Unsupported targetType: " + value);
    }

    private static Frequency parseFrequency(String value) {
        if (StringValueSupport.isBlank(value)) {
            throw new IllegalArgumentException("frequency is required");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
        case "daily" -> Frequency.DAILY;
        case "weekdays" -> Frequency.WEEKDAYS;
        case "weekly" -> Frequency.WEEKLY;
        case "custom" -> Frequency.CUSTOM;
        default -> throw new IllegalArgumentException("Unsupported frequency: " + value);
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
            throw new IllegalArgumentException("Unsupported mode: " + value);
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
                throw new IllegalArgumentException("cronExpression is required for advanced mode");
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
            throw new IllegalArgumentException("At least one weekday is required for weekly/custom frequency");
        }

        Set<Integer> normalizedDays = new HashSet<>();
        for (Integer day : requestedDays) {
            if (day == null || !WEEKDAY_SET.contains(day)) {
                throw new IllegalArgumentException("Weekday must be between 1 and 7");
            }
            normalizedDays.add(day);
        }

        if (normalizedDays.isEmpty()) {
            throw new IllegalArgumentException("At least one weekday is required for weekly/custom frequency");
        }

        return normalizedDays;
    }

    private static TimeValue parseTimeValue(String value) {
        if (StringValueSupport.isBlank(value)) {
            throw new IllegalArgumentException("time is required");
        }

        String normalized = value.trim();
        if (normalized.matches("\\d{4}")) {
            int hour = Integer.parseInt(normalized.substring(0, 2));
            int minute = Integer.parseInt(normalized.substring(2, 4));
            return toTimeValue(hour, minute);
        }

        String[] parts = normalized.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid time format. Use HH:mm or HHmm");
        }

        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return toTimeValue(hour, minute);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid time format. Use HH:mm or HHmm");
        }
    }

    private static TimeValue toTimeValue(int hour, int minute) {
        boolean validHour = hour >= 0 && hour <= 23;
        boolean validMinute = minute >= 0 && minute <= 59;
        if (!validHour || !validMinute) {
            throw new IllegalArgumentException("Invalid time value. Hour must be 0..23 and minute 0..59");
        }
        return new TimeValue(hour, minute);
    }

    private static int normalizeMaxExecutions(Integer value) {
        if (value == null) {
            return -1;
        }

        if (value < 0) {
            throw new IllegalArgumentException("maxExecutions must be >= 0");
        }

        if (value == 0) {
            return -1;
        }

        return value;
    }

    private static boolean normalizeEnabled(Boolean value) {
        return value == null || value;
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
                .map(SchedulerFacade::weekdayToCron)
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
        default -> throw new IllegalArgumentException("Weekday must be between 1 and 7");
        };
    }

    private static String requireId(String value, String fieldName) {
        if (StringValueSupport.isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
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
            throw new IllegalArgumentException("report.operation is required");
        }

        String operation = request.operation().trim().toUpperCase(Locale.ROOT);
        return switch (operation) {
        case "CLEAR" -> ScheduleReportConfigUpdate.clear();
        case "SET" -> {
            ScheduleReportConfig report = normalizeReportRequest(request.config());
            if (report == null) {
                throw new IllegalArgumentException("report.config is required when report.operation is SET");
            }
            yield ScheduleReportConfigUpdate.set(report);
        }
        default -> throw new IllegalArgumentException("Unsupported report.operation: " + request.operation());
        };
    }

    private ScheduleReportView toScheduleReportView(ScheduleReportConfig report) {
        if (report == null) {
            return null;
        }
        return new ScheduleReportView(
                report.getChannelType(),
                report.getChatId(),
                report.getWebhookUrl(),
                report.getWebhookBearerToken());
    }

    private void validateReportChannel(ScheduleReportConfig report) {
        String channelType = report.getChannelType();
        String chatId = report.getChatId();
        String webhookUrl = report.getWebhookUrl();
        String webhookBearerToken = report.getWebhookBearerToken();

        if (channelType == null && chatId != null) {
            throw new IllegalArgumentException("reportChannelType is required when reportChatId is set");
        }
        if (channelType == null) {
            throw new IllegalArgumentException("reportChannelType is required when report settings are set");
        }
        if (ChannelTypes.WEBHOOK.equals(channelType)) {
            if (!StringValueSupport.isBlank(chatId)) {
                throw new IllegalArgumentException("reportChatId is not supported for webhook channel type");
            }
            if (StringValueSupport.isBlank(webhookUrl)) {
                throw new IllegalArgumentException("reportWebhookUrl is required for webhook channel type");
            }
            if (!webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
                throw new IllegalArgumentException("reportWebhookUrl must start with http:// or https://");
            }
            return;
        }

        if (webhookUrl != null || webhookBearerToken != null) {
            throw new IllegalArgumentException("Webhook settings are only supported for webhook channel type");
        }
        if (channelRuntimePort.findChannel(channelType).isEmpty()) {
            throw new IllegalArgumentException("Unknown channel type: " + channelType);
        }
    }

    private static String normalizeOptionalString(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private enum Frequency {
        DAILY, WEEKDAYS, WEEKLY, CUSTOM
    }

    private enum ScheduleMode {
        SIMPLE, ADVANCED
    }

    private record TimeValue(int hour, int minute) {
    }

    public record CreateScheduleCommand(
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
    }

    public record UpdateScheduleCommand(
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

    public record SchedulerStateView(
            boolean featureEnabled,
            boolean autoModeEnabled,
            List<GoalView> goals,
            List<TaskView> standaloneTasks,
            List<ScheduledTaskView> scheduledTasks,
            List<ScheduleView> schedules,
            List<ScheduleReportChannelOptionView> reportChannelOptions) {
    }

    public record GoalView(
            String id,
            String title,
            String description,
            String prompt,
            String status,
            long completedTasks,
            int totalTasks,
            List<TaskView> tasks) {
    }

    public record TaskView(
            String id,
            String goalId,
            String title,
            String description,
            String prompt,
            String status,
            int order,
            boolean standalone) {
    }

    public record ScheduledTaskView(
            String id,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            boolean reflectionTierPriority,
            String legacySourceType,
            String legacySourceId) {
    }

    public record ScheduleView(
            String id,
            String type,
            String targetId,
            String targetLabel,
            String cronExpression,
            boolean enabled,
            boolean clearContextBeforeRun,
            ScheduleReportView report,
            int maxExecutions,
            int executionCount,
            Instant createdAt,
            Instant updatedAt,
            Instant lastExecutedAt,
            Instant nextExecutionAt) {
    }

    public record ScheduleReportView(
            String channelType,
            String chatId,
            String webhookUrl,
            String webhookBearerToken) {
    }

    public record ScheduleReportChannelOptionView(
            String type,
            String label,
            String suggestedChatId) {
    }
}
