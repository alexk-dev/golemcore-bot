package me.golemcore.bot.application.command;

import java.util.List;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.DiaryEntry;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.DelayedSessionActionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;

public class AutomationCommandService {

    private static final int MIN_SCHEDULE_ARGS = 2;

    private final AutoModeService autoModeService;
    private final RuntimeConfigService runtimeConfigService;
    private final ScheduleService scheduleService;
    private final DelayedActionPolicyService delayedActionPolicyService;
    private final DelayedSessionActionService delayedSessionActionService;

    public AutomationCommandService(
            AutoModeService autoModeService,
            RuntimeConfigService runtimeConfigService,
            ScheduleService scheduleService,
            DelayedActionPolicyService delayedActionPolicyService,
            DelayedSessionActionService delayedSessionActionService) {
        this.autoModeService = autoModeService;
        this.runtimeConfigService = runtimeConfigService;
        this.scheduleService = scheduleService;
        this.delayedActionPolicyService = delayedActionPolicyService;
        this.delayedSessionActionService = delayedSessionActionService;
    }

    public boolean isAutoFeatureEnabled() {
        return autoModeService.isFeatureEnabled();
    }

    public boolean isAutoModeEnabled() {
        return autoModeService.isAutoModeEnabled();
    }

    public List<Goal> getActiveGoals(String sessionId) {
        if (isBlank(sessionId)) {
            return List.copyOf(autoModeService.getActiveGoals());
        }
        return List.copyOf(autoModeService.getActiveGoals(sessionId));
    }

    public List<Goal> getActiveGoals() {
        return List.copyOf(autoModeService.getActiveGoals());
    }

    public java.util.Optional<AutoTask> getNextPendingTask(String sessionId) {
        if (isBlank(sessionId)) {
            return autoModeService.getNextPendingTask();
        }
        return autoModeService.getNextPendingTask(sessionId);
    }

    public java.util.Optional<AutoTask> getNextPendingTask() {
        return autoModeService.getNextPendingTask();
    }

    public boolean isLaterFeatureEnabled() {
        return runtimeConfigService.isDelayedActionsEnabled();
    }

    public AutoOutcome getAutoStatus() {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        return new AutoStatus(autoModeService.isAutoModeEnabled());
    }

    public AutoOutcome enableAutoMode() {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        autoModeService.enableAutoMode();
        return new AutoEnabled();
    }

    public AutoOutcome disableAutoMode() {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        autoModeService.disableAutoMode();
        return new AutoDisabled();
    }

    public GoalsOutcome getGoals(String sessionId) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<Goal> goals = isBlank(sessionId) ? autoModeService.getGoals() : autoModeService.getGoals(sessionId);
        if (goals.isEmpty()) {
            return new EmptyGoals();
        }
        return new GoalsOverview(List.copyOf(goals));
    }

    public GoalsOutcome getGoals() {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<Goal> goals = autoModeService.getGoals();
        if (goals.isEmpty()) {
            return new EmptyGoals();
        }
        return new GoalsOverview(List.copyOf(goals));
    }

    public GoalCreationOutcome createGoal(String sessionId, String description) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        if (description == null || description.isBlank()) {
            return new GoalDescriptionRequired();
        }
        Goal goal = isBlank(sessionId)
                ? autoModeService.createGoal(description, null)
                : autoModeService.createGoal(sessionId, description, null, null, null, false);
        return new GoalCreated(goal);
    }

    public GoalCreationOutcome createGoal(String description) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        if (description == null || description.isBlank()) {
            return new GoalDescriptionRequired();
        }
        Goal goal = autoModeService.createGoal(description, null);
        return new GoalCreated(goal);
    }

    public TasksOutcome getTasks(String sessionId) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<Goal> goals = isBlank(sessionId) ? autoModeService.getGoals() : autoModeService.getGoals(sessionId);
        boolean hasTasks = goals.stream().anyMatch(goal -> !goal.getTasks().isEmpty());
        if (goals.isEmpty() || !hasTasks) {
            return new EmptyTasks();
        }
        return new TasksOverview(List.copyOf(goals));
    }

    public TasksOutcome getTasks() {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<Goal> goals = autoModeService.getGoals();
        boolean hasTasks = goals.stream().anyMatch(goal -> !goal.getTasks().isEmpty());
        if (goals.isEmpty() || !hasTasks) {
            return new EmptyTasks();
        }
        return new TasksOverview(List.copyOf(goals));
    }

    public DiaryOutcome getDiary(String sessionId, int count) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<DiaryEntry> entries = isBlank(sessionId)
                ? autoModeService.getRecentDiary(count)
                : autoModeService.getRecentDiary(sessionId, count);
        if (entries.isEmpty()) {
            return new EmptyDiary();
        }
        return new DiaryOverview(List.copyOf(entries));
    }

    public DiaryOutcome getDiary(int count) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<DiaryEntry> entries = autoModeService.getRecentDiary(count);
        if (entries.isEmpty()) {
            return new EmptyDiary();
        }
        return new DiaryOverview(List.copyOf(entries));
    }

    public ScheduleOutcome listSchedules() {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        List<ScheduleEntry> schedules = scheduleService.getSchedules();
        if (schedules.isEmpty()) {
            return new EmptySchedules();
        }
        return new SchedulesOverview(List.copyOf(schedules));
    }

    public ScheduleOutcome createGoalSchedule(String goalId, List<String> args) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        if (args.size() < MIN_SCHEDULE_ARGS) {
            return new GoalScheduleUsage();
        }
        return new InvalidCron("Goal schedules are no longer supported");
    }

    public ScheduleOutcome createTaskSchedule(String taskId, List<String> args) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        if (args.size() < MIN_SCHEDULE_ARGS) {
            return new TaskScheduleUsage();
        }
        return new InvalidCron("Task schedules are no longer supported");
    }

    public ScheduleOutcome deleteSchedule(String scheduleId) {
        if (!autoModeService.isFeatureEnabled()) {
            return new AutoFeatureUnavailable();
        }
        if (scheduleId == null || scheduleId.isBlank()) {
            return new DeleteScheduleUsage();
        }
        try {
            scheduleService.deleteSchedule(scheduleId);
            return new ScheduleDeleted(scheduleId);
        } catch (IllegalArgumentException exception) {
            return new ScheduleNotFound(scheduleId);
        }
    }

    public LaterOutcome listLaterActions(String channelType, String conversationKey) {
        if (!isLaterAvailable(channelType, conversationKey)) {
            return new LaterUnavailable();
        }
        List<DelayedSessionAction> actions = delayedSessionActionService.listActions(channelType, conversationKey);
        if (actions.isEmpty()) {
            return new EmptyLaterActions();
        }
        return new LaterActionsOverview(List.copyOf(actions));
    }

    public LaterActionOutcome cancelLaterAction(String actionId, String channelType, String conversationKey) {
        if (!isLaterAvailable(channelType, conversationKey)) {
            return new LaterUnavailable();
        }
        if (actionId == null || actionId.isBlank()) {
            return new LaterCancelUsage();
        }
        boolean cancelled = delayedSessionActionService.cancelAction(actionId, channelType, conversationKey);
        if (!cancelled) {
            return new LaterNotFound(actionId);
        }
        return new LaterCancelled(actionId);
    }

    public LaterActionOutcome runLaterActionNow(String actionId, String channelType, String conversationKey) {
        if (!isLaterAvailable(channelType, conversationKey)) {
            return new LaterUnavailable();
        }
        if (actionId == null || actionId.isBlank()) {
            return new LaterRunNowUsage();
        }
        boolean updated = delayedSessionActionService.runNow(actionId, channelType, conversationKey);
        if (!updated) {
            return new LaterNotFound(actionId);
        }
        return new LaterRunNow(actionId);
    }

    public boolean canScheduleActions(String channelType) {
        return delayedActionPolicyService == null || delayedActionPolicyService.canScheduleActions(channelType);
    }

    private boolean isLaterAvailable(String channelType, String conversationKey) {
        if (!runtimeConfigService.isDelayedActionsEnabled()) {
            return false;
        }
        if (delayedSessionActionService == null) {
            return false;
        }
        if (channelType == null || conversationKey == null) {
            return false;
        }
        return delayedActionPolicyService == null || delayedActionPolicyService.canScheduleActions(channelType);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public sealed

    interface AutoOutcome
    permits AutoFeatureUnavailable, AutoStatus, AutoEnabled, AutoDisabled
    {
        }

        public sealed

        interface GoalsOutcome
        permits AutoFeatureUnavailable, EmptyGoals, GoalsOverview
        {
            }

            public sealed

            interface GoalCreationOutcome
            permits AutoFeatureUnavailable, GoalDescriptionRequired, GoalCreated
            {
                }

                public sealed

                interface TasksOutcome
                permits AutoFeatureUnavailable, EmptyTasks, TasksOverview
                {
                    }

                    public sealed

                    interface DiaryOutcome
                    permits AutoFeatureUnavailable, EmptyDiary, DiaryOverview
                    {
                        }

                        public sealed

                        interface ScheduleOutcome
                        permits AutoFeatureUnavailable, EmptySchedules, SchedulesOverview,
            GoalScheduleUsage, TaskScheduleUsage, DeleteScheduleUsage, GoalNotFound, TaskNotFound, ScheduleCreated,
            ScheduleDeleted, ScheduleNotFound, InvalidCron
                        {
                            }

                            public sealed

                            interface LaterOutcome
                            permits LaterUnavailable, EmptyLaterActions, LaterActionsOverview
                            {
                                }

                                public sealed

                                interface LaterActionOutcome
                                permits LaterUnavailable, LaterCancelUsage, LaterRunNowUsage,
                                        LaterNotFound, LaterCancelled, LaterRunNow
                                {
    }

    public record AutoFeatureUnavailable()
            implements AutoOutcome, GoalsOutcome, GoalCreationOutcome, TasksOutcome, DiaryOutcome, ScheduleOutcome {
    }

    public record AutoStatus(boolean enabled) implements AutoOutcome {
    }

    public record AutoEnabled() implements AutoOutcome {
    }

    public record AutoDisabled() implements AutoOutcome {
    }

    public record EmptyGoals() implements GoalsOutcome {
    }

    public record GoalsOverview(List<Goal> goals) implements GoalsOutcome {
    }

    public record GoalDescriptionRequired() implements GoalCreationOutcome {
    }

    public record GoalCreated(Goal goal) implements GoalCreationOutcome {
    }

    public record EmptyTasks() implements TasksOutcome {
    }

    public record TasksOverview(List<Goal> goals) implements TasksOutcome {
    }

    public record EmptyDiary() implements DiaryOutcome {
    }

    public record DiaryOverview(List<DiaryEntry> entries) implements DiaryOutcome {
    }

    public record EmptySchedules() implements ScheduleOutcome {
    }

    public record SchedulesOverview(List<ScheduleEntry> schedules) implements ScheduleOutcome {
    }

    public record GoalScheduleUsage() implements ScheduleOutcome {
    }

    public record TaskScheduleUsage() implements ScheduleOutcome {
    }

    public record DeleteScheduleUsage() implements ScheduleOutcome {
    }

    public record GoalNotFound(String goalId) implements ScheduleOutcome {
    }

    public record TaskNotFound(String taskId) implements ScheduleOutcome {
    }

    public record ScheduleCreated(String scheduleId, String cronExpression) implements ScheduleOutcome {
    }

    public record ScheduleDeleted(String scheduleId) implements ScheduleOutcome {
    }

    public record ScheduleNotFound(String scheduleId) implements ScheduleOutcome {
    }

    public record InvalidCron(String message) implements ScheduleOutcome {
    }

    public record LaterUnavailable() implements LaterOutcome, LaterActionOutcome {
    }

    public record EmptyLaterActions() implements LaterOutcome {
    }

    public record LaterActionsOverview(List<DelayedSessionAction> actions) implements LaterOutcome {
    }

    public record LaterCancelUsage() implements LaterActionOutcome {
    }

    public record LaterRunNowUsage() implements LaterActionOutcome {
    }

    public record LaterNotFound(String actionId) implements LaterActionOutcome {
    }

    public record LaterCancelled(String actionId) implements LaterActionOutcome {
    }

    public record LaterRunNow(String actionId) implements LaterActionOutcome {
    }
}
