package me.golemcore.bot.adapter.inbound.command;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.application.command.AutomationCommandService;
import me.golemcore.bot.domain.model.AutoModeChannelRegisteredEvent;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class AutomationCommandHandler {

    private static final String MSG_AUTO_NOT_AVAILABLE = "command.auto.not-available";
    private static final String DOUBLE_NEWLINE = "\n\n";
    private static final String SUBCMD_LIST = "list";
    private static final String CMD_GOAL = "goal";
    private static final String CMD_HELP = "help";
    private static final DateTimeFormatter LATER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final AutomationCommandService automationCommandService;
    private final UserPreferencesService preferencesService;
    private final ApplicationEventPublisher eventPublisher;

    boolean isAutoFeatureEnabled() {
        return automationCommandService.isAutoFeatureEnabled();
    }

    boolean isAutoModeEnabled() {
        return automationCommandService.isAutoModeEnabled();
    }

    List<Goal> getActiveGoals() {
        return automationCommandService.getActiveGoals();
    }

    Optional<AutoTask> getNextPendingTask() {
        return automationCommandService.getNextPendingTask();
    }

    boolean isLaterFeatureEnabled() {
        return automationCommandService.isLaterFeatureEnabled();
    }

    CommandPort.CommandResult handleAuto(
            List<String> args,
            String channelType,
            String sessionChatId,
            String transportChatId) {
        if (!automationCommandService.isAutoFeatureEnabled()) {
            return CommandPort.CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            AutomationCommandService.AutoOutcome outcome = automationCommandService.getAutoStatus();
            AutomationCommandService.AutoStatus status = (AutomationCommandService.AutoStatus) outcome;
            return CommandPort.CommandResult.success(msg("command.auto.status", status.enabled() ? "ON" : "OFF"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
        case "on" -> {
            automationCommandService.enableAutoMode();
            if (channelType != null && sessionChatId != null) {
                eventPublisher.publishEvent(new AutoModeChannelRegisteredEvent(
                        channelType,
                        sessionChatId,
                        transportChatId != null ? transportChatId : sessionChatId));
            }
            yield CommandPort.CommandResult.success(msg("command.auto.enabled"));
        }
        case "off" -> {
            automationCommandService.disableAutoMode();
            yield CommandPort.CommandResult.success(msg("command.auto.disabled"));
        }
        default -> CommandPort.CommandResult.success(msg("command.auto.usage"));
        };
    }

    CommandPort.CommandResult handleGoals() {
        AutomationCommandService.GoalsOutcome outcome = automationCommandService.getGoals();
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandPort.CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.EmptyGoals) {
            return CommandPort.CommandResult.success(msg("command.goals.empty"));
        }
        List<Goal> goals = ((AutomationCommandService.GoalsOverview) outcome).goals();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.goals.title", goals.size())).append(DOUBLE_NEWLINE);

        for (Goal goal : goals) {
            long completed = goal.getCompletedTaskCount();
            int total = goal.getTasks().size();
            String statusIcon = switch (goal.getStatus()) {
            case ACTIVE -> "▶️";
            case COMPLETED -> "✅";
            case PAUSED -> "⏸️";
            case CANCELLED -> "❌";
            };
            builder.append(String.format("%s **%s** [%s] (%d/%d tasks) `goal_id: %s`%n",
                    statusIcon, goal.getTitle(), goal.getStatus(), completed, total, goal.getId()));
            if (goal.getDescription() != null && !goal.getDescription().isBlank()) {
                builder.append("  ").append(goal.getDescription()).append("\n");
            }
        }

        return CommandPort.CommandResult.success(builder.toString());
    }

    CommandPort.CommandResult handleGoal(List<String> args) {
        if (args.isEmpty()) {
            return CommandPort.CommandResult.success(msg("command.goals.empty"));
        }

        AutomationCommandService.GoalCreationOutcome outcome = automationCommandService
                .createGoal(String.join(" ", args));
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandPort.CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.GoalCreated created) {
            return CommandPort.CommandResult.success(msg("command.goal.created", created.goal().getTitle()));
        }
        if (outcome instanceof AutomationCommandService.GoalLimitReached limitReached) {
            return CommandPort.CommandResult.failure(msg("command.goal.limit", limitReached.maxGoals()));
        }
        return CommandPort.CommandResult.success(msg("command.goals.empty"));
    }

    CommandPort.CommandResult handleTasks() {
        AutomationCommandService.TasksOutcome outcome = automationCommandService.getTasks();
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandPort.CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.EmptyTasks) {
            return CommandPort.CommandResult.success(msg("command.tasks.empty"));
        }
        List<Goal> goals = ((AutomationCommandService.TasksOverview) outcome).goals();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.tasks.title")).append(DOUBLE_NEWLINE);

        for (Goal goal : goals) {
            if (goal.getTasks().isEmpty()) {
                continue;
            }

            builder.append(msg("command.tasks.goal", goal.getTitle()));
            builder.append(" [").append(goal.getStatus()).append("] `goal_id: ").append(goal.getId()).append("`\n");

            List<AutoTask> sortedTasks = goal.getTasks().stream()
                    .sorted(Comparator.comparingInt(AutoTask::getOrder))
                    .toList();

            for (AutoTask task : sortedTasks) {
                String icon = switch (task.getStatus()) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[>]";
                case COMPLETED -> "[x]";
                case FAILED -> "[!]";
                case SKIPPED -> "[-]";
                };
                builder.append("  ").append(icon).append(" ").append(task.getTitle())
                        .append(" `task_id: ").append(task.getId()).append("`\n");
            }
            builder.append("\n");
        }

        return CommandPort.CommandResult.success(builder.toString().trim());
    }

    CommandPort.CommandResult handleDiary(List<String> args) {
        int count = 10;
        if (!args.isEmpty()) {
            try {
                count = Integer.parseInt(args.get(0));
                count = Math.max(1, Math.min(count, 50));
            } catch (NumberFormatException ignored) {
            }
        }

        AutomationCommandService.DiaryOutcome outcome = automationCommandService.getDiary(count);
        if (outcome instanceof AutomationCommandService.AutoFeatureUnavailable) {
            return CommandPort.CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }
        if (outcome instanceof AutomationCommandService.EmptyDiary) {
            return CommandPort.CommandResult.success(msg("command.diary.empty"));
        }
        List<DiaryEntry> entries = ((AutomationCommandService.DiaryOverview) outcome).entries();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.diary.title", entries.size())).append(DOUBLE_NEWLINE);

        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
        for (DiaryEntry entry : entries) {
            builder.append("[").append(timeFormat.format(entry.getTimestamp())).append("] ");
            builder.append("**").append(entry.getType()).append("**: ");
            builder.append(entry.getContent()).append("\n");
        }

        return CommandPort.CommandResult.success(builder.toString());
    }

    CommandPort.CommandResult handleSchedule(List<String> args) {
        if (!automationCommandService.isAutoFeatureEnabled()) {
            return CommandPort.CommandResult.success(msg(MSG_AUTO_NOT_AVAILABLE));
        }

        if (args.isEmpty()) {
            return handleScheduleList();
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        List<String> subArgs = args.subList(1, args.size());

        return switch (subcommand) {
        case CMD_GOAL -> handleScheduleGoal(subArgs);
        case "task" -> handleScheduleTask(subArgs);
        case SUBCMD_LIST -> handleScheduleList();
        case "delete" -> handleScheduleDelete(subArgs);
        case CMD_HELP -> CommandPort.CommandResult.success(msg("command.schedule.help.text"));
        default -> CommandPort.CommandResult.success(msg("command.schedule.usage"));
        };
    }

    CommandPort.CommandResult handleLater(List<String> args, String channelType, String conversationKey) {
        if (!automationCommandService.isLaterFeatureEnabled()) {
            return CommandPort.CommandResult.success(msg("command.later.not-available"));
        }
        if (args.isEmpty()) {
            return CommandPort.CommandResult.success(msg("command.later.usage"));
        }

        String subcommand = args.get(0).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
        case SUBCMD_LIST -> handleLaterList(channelType, conversationKey);
        case "cancel" -> handleLaterCancel(args.subList(1, args.size()), channelType, conversationKey);
        case "now" -> handleLaterRunNow(args.subList(1, args.size()), channelType, conversationKey);
        case CMD_HELP -> CommandPort.CommandResult.success(msg("command.later.help.text"));
        default -> CommandPort.CommandResult.success(msg("command.later.usage"));
        };
    }

    private CommandPort.CommandResult handleScheduleGoal(List<String> args) {
        if (args.isEmpty()) {
            return CommandPort.CommandResult.success(msg("command.schedule.goal.usage"));
        }
        String goalId = args.get(0);
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.createGoalSchedule(goalId, args);
        return renderScheduleOutcome(outcome);
    }

    private CommandPort.CommandResult handleScheduleTask(List<String> args) {
        if (args.isEmpty()) {
            return CommandPort.CommandResult.success(msg("command.schedule.task.usage"));
        }
        String taskId = args.get(0);
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.createTaskSchedule(taskId, args);
        return renderScheduleOutcome(outcome);
    }

    private CommandPort.CommandResult handleScheduleList() {
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService.listSchedules();
        if (outcome instanceof AutomationCommandService.EmptySchedules) {
            return CommandPort.CommandResult.success(msg("command.schedule.list.empty"));
        }
        List<ScheduleEntry> schedules = ((AutomationCommandService.SchedulesOverview) outcome).schedules();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.schedule.list.title", schedules.size())).append(DOUBLE_NEWLINE);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
        for (ScheduleEntry entry : schedules) {
            String enabledIcon = entry.isEnabled() ? "✅" : "❌";
            builder.append(String.format("%s `%s` [%s] -> %s%n",
                    enabledIcon, entry.getId(), entry.getType(), entry.getTargetId()));
            builder.append("  Cron: `").append(entry.getCronExpression()).append("`");
            if (entry.getMaxExecutions() > 0) {
                builder.append(String.format(" (%d/%d runs)", entry.getExecutionCount(), entry.getMaxExecutions()));
            } else {
                builder.append(String.format(" (%d runs)", entry.getExecutionCount()));
            }
            builder.append("\n");
            if (entry.getNextExecutionAt() != null) {
                builder.append("  Next: ").append(formatter.format(entry.getNextExecutionAt())).append(" UTC\n");
            }
        }

        return CommandPort.CommandResult.success(builder.toString());
    }

    private CommandPort.CommandResult handleScheduleDelete(List<String> args) {
        AutomationCommandService.ScheduleOutcome outcome = automationCommandService
                .deleteSchedule(args.isEmpty() ? null : args.get(0));
        if (outcome instanceof AutomationCommandService.DeleteScheduleUsage) {
            return CommandPort.CommandResult.success(msg("command.schedule.delete.usage"));
        }
        if (outcome instanceof AutomationCommandService.ScheduleDeleted deleted) {
            return CommandPort.CommandResult.success(msg("command.schedule.deleted", deleted.scheduleId()));
        }
        AutomationCommandService.ScheduleNotFound scheduleNotFound = (AutomationCommandService.ScheduleNotFound) outcome;
        return CommandPort.CommandResult.failure(msg("command.schedule.not-found", scheduleNotFound.scheduleId()));
    }

    private CommandPort.CommandResult handleLaterList(String channelType, String conversationKey) {
        AutomationCommandService.LaterOutcome outcome = automationCommandService.listLaterActions(channelType,
                conversationKey);
        if (outcome instanceof AutomationCommandService.LaterUnavailable) {
            return CommandPort.CommandResult.success(msg("command.later.not-available"));
        }
        if (outcome instanceof AutomationCommandService.EmptyLaterActions) {
            return CommandPort.CommandResult.success(msg("command.later.list.empty"));
        }
        List<DelayedSessionAction> actions = ((AutomationCommandService.LaterActionsOverview) outcome).actions();

        StringBuilder builder = new StringBuilder();
        builder.append(msg("command.later.list.title", actions.size())).append(DOUBLE_NEWLINE);
        for (DelayedSessionAction action : actions) {
            builder.append("`").append(action.getId()).append("` ");
            builder.append(resolveLaterSummary(action)).append("\n");
            builder.append(msg("command.later.list.status")).append(": ");
            builder.append(resolveLaterStatus(action)).append("\n");
            builder.append(msg("command.later.list.next-check")).append(": ");
            builder.append(formatLaterRunAt(action));
            if (action.isCancelOnUserActivity()) {
                builder.append("\n").append(msg("command.later.list.cancel-on-activity"));
            }
            builder.append(DOUBLE_NEWLINE);
        }
        return CommandPort.CommandResult.success(builder.toString().stripTrailing());
    }

    private CommandPort.CommandResult handleLaterCancel(List<String> args, String channelType, String conversationKey) {
        AutomationCommandService.LaterActionOutcome outcome = automationCommandService.cancelLaterAction(
                args.isEmpty() ? null : args.get(0),
                channelType,
                conversationKey);
        if (outcome instanceof AutomationCommandService.LaterUnavailable) {
            return CommandPort.CommandResult.success(msg("command.later.not-available"));
        }
        if (outcome instanceof AutomationCommandService.LaterCancelUsage) {
            return CommandPort.CommandResult.success(msg("command.later.cancel.usage"));
        }
        if (outcome instanceof AutomationCommandService.LaterCancelled cancelled) {
            return CommandPort.CommandResult.success(msg("command.later.cancelled", cancelled.actionId()));
        }
        return CommandPort.CommandResult.failure(
                msg("command.later.not-found", ((AutomationCommandService.LaterNotFound) outcome).actionId()));
    }

    private CommandPort.CommandResult handleLaterRunNow(List<String> args, String channelType, String conversationKey) {
        AutomationCommandService.LaterActionOutcome outcome = automationCommandService.runLaterActionNow(
                args.isEmpty() ? null : args.get(0),
                channelType,
                conversationKey);
        if (outcome instanceof AutomationCommandService.LaterUnavailable) {
            return CommandPort.CommandResult.success(msg("command.later.not-available"));
        }
        if (outcome instanceof AutomationCommandService.LaterRunNowUsage) {
            return CommandPort.CommandResult.success(msg("command.later.now.usage"));
        }
        if (outcome instanceof AutomationCommandService.LaterRunNow runNow) {
            return CommandPort.CommandResult.success(msg("command.later.now.done", runNow.actionId()));
        }
        return CommandPort.CommandResult.failure(
                msg("command.later.not-found", ((AutomationCommandService.LaterNotFound) outcome).actionId()));
    }

    private CommandPort.CommandResult renderScheduleOutcome(AutomationCommandService.ScheduleOutcome outcome) {
        if (outcome instanceof AutomationCommandService.GoalScheduleUsage) {
            return CommandPort.CommandResult.success(msg("command.schedule.goal.usage"));
        }
        if (outcome instanceof AutomationCommandService.TaskScheduleUsage) {
            return CommandPort.CommandResult.success(msg("command.schedule.task.usage"));
        }
        if (outcome instanceof AutomationCommandService.GoalNotFound goalNotFound) {
            return CommandPort.CommandResult.failure(msg("command.schedule.goal.not-found", goalNotFound.goalId()));
        }
        if (outcome instanceof AutomationCommandService.TaskNotFound taskNotFound) {
            return CommandPort.CommandResult.failure(msg("command.schedule.task.not-found", taskNotFound.taskId()));
        }
        if (outcome instanceof AutomationCommandService.InvalidCron invalidCron) {
            return CommandPort.CommandResult.failure(msg("command.schedule.invalid-cron", invalidCron.message()));
        }
        AutomationCommandService.ScheduleCreated created = (AutomationCommandService.ScheduleCreated) outcome;
        return CommandPort.CommandResult.success(msg(
                "command.schedule.created",
                created.scheduleId(),
                created.cronExpression()));
    }

    private String resolveLaterSummary(DelayedSessionAction action) {
        String humanSummary = payloadString(action, "humanSummary");
        if (humanSummary != null) {
            return humanSummary;
        }
        String message = payloadString(action, "message");
        String originalSummary = payloadString(action, "originalSummary");
        if (action.getKind() == null) {
            return msg("command.later.kind.default");
        }
        return switch (action.getKind()) {
        case REMIND_LATER -> message != null ? msg("command.later.kind.reminder.with-message", message)
                : msg("command.later.kind.reminder");
        case RUN_LATER -> originalSummary != null ? msg("command.later.kind.check-back.with-summary", originalSummary)
                : msg("command.later.kind.check-back");
        case NOTIFY_JOB_READY -> message != null ? message : msg("command.later.kind.job-result");
        };
    }

    private String resolveLaterStatus(DelayedSessionAction action) {
        if (action == null || action.getStatus() == null) {
            return msg("command.later.status.unknown");
        }
        return switch (action.getStatus()) {
        case SCHEDULED -> msg("command.later.status.scheduled");
        case LEASED -> msg("command.later.status.leased");
        case COMPLETED -> msg("command.later.status.completed");
        case CANCELLED -> msg("command.later.status.cancelled");
        case DEAD_LETTER -> msg("command.later.status.dead-letter");
        };
    }

    private String formatLaterRunAt(DelayedSessionAction action) {
        if (action == null || action.getRunAt() == null) {
            return msg("command.later.list.no-time");
        }
        ZoneId zoneId = resolveUserZoneId();
        return LATER_TIME_FORMATTER.format(action.getRunAt().atZone(zoneId));
    }

    private ZoneId resolveUserZoneId() {
        try {
            UserPreferences preferences = preferencesService.getPreferences();
            if (preferences != null && preferences.getTimezone() != null && !preferences.getTimezone().isBlank()) {
                return ZoneId.of(preferences.getTimezone().trim());
            }
        } catch (RuntimeException exception) {
            log.debug("Falling back to UTC for delayed action time formatting: {}", exception.getMessage());
        }
        return ZoneOffset.UTC;
    }

    private String payloadString(DelayedSessionAction action, String key) {
        if (action == null || action.getPayload() == null) {
            return null;
        }
        Object value = action.getPayload().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue.trim() : null;
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
