package me.golemcore.bot.domain.auto;

import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.domain.scheduling.ScheduleService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds dashboard-facing views of autonomous scheduler runs by grouping
 * persisted session messages by auto run identifier.
 */
@Service
public class AutoRunHistoryService {

    private final SessionPort sessionPort;
    private final ScheduleService scheduleService;
    private final AutoModeService autoModeService;

    public AutoRunHistoryService(
            SessionPort sessionPort,
            ScheduleService scheduleService,
            AutoModeService autoModeService) {
        this.sessionPort = sessionPort;
        this.scheduleService = scheduleService;
        this.autoModeService = autoModeService;
    }

    public List<RunSummary> listRuns(String scheduleId, String goalId, String taskId, int limit) {
        List<RunAggregate> runs = collectRuns();
        return runs.stream()
                .filter(run -> matchesFilters(run, scheduleId, goalId, taskId))
                .sorted(Comparator.comparing(RunAggregate::sortInstant,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .map(RunAggregate::toSummary)
                .toList();
    }

    public Optional<RunDetail> getRun(String runId) {
        if (StringValueSupport.isBlank(runId)) {
            return Optional.empty();
        }

        return collectRuns().stream()
                .filter(run -> runId.equals(run.runId))
                .findFirst()
                .map(RunAggregate::toDetail);
    }

    private List<RunAggregate> collectRuns() {
        Map<String, Goal> goalById = buildGoalById();
        Map<String, AutoTask> taskById = buildTaskById(goalById);
        Map<String, me.golemcore.bot.domain.model.ScheduledTask> scheduledTaskById = buildScheduledTaskById();
        Map<String, ScheduleEntry> scheduleById = buildScheduleById();

        Map<String, RunAggregate> runsById = new LinkedHashMap<>();
        List<AgentSession> sessions = sessionPort.listAll();
        for (AgentSession session : sessions) {
            List<Message> messages = session.getMessages();
            if (messages == null || messages.isEmpty()) {
                continue;
            }

            String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
            String transportChatId = SessionIdentitySupport.resolveTransportChatId(session);
            for (Message message : messages) {
                Map<String, Object> metadata = message.getMetadata();
                String runId = AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_RUN_ID);
                if (StringValueSupport.isBlank(runId)) {
                    continue;
                }

                RunAggregate aggregate = runsById.computeIfAbsent(runId, ignored -> new RunAggregate(
                        runId,
                        session.getId(),
                        session.getChannelType(),
                        conversationKey,
                        transportChatId));
                aggregate.captureContext(metadata, scheduleById, goalById, taskById, scheduledTaskById);
                aggregate.addMessage(message);
            }
        }

        return new ArrayList<>(runsById.values());
    }

    private boolean matchesFilters(RunAggregate run, String scheduleId, String goalId, String taskId) {
        boolean scheduleMatches = StringValueSupport.isBlank(scheduleId) || scheduleId.equals(run.scheduleId);
        boolean goalMatches = StringValueSupport.isBlank(goalId) || goalId.equals(run.goalId);
        boolean taskMatches = StringValueSupport.isBlank(taskId) || taskId.equals(run.taskId);
        return scheduleMatches && goalMatches && taskMatches;
    }

    private Map<String, me.golemcore.bot.domain.model.ScheduledTask> buildScheduledTaskById() {
        Map<String, me.golemcore.bot.domain.model.ScheduledTask> scheduledTaskById = new LinkedHashMap<>();
        for (me.golemcore.bot.domain.model.ScheduledTask scheduledTask : autoModeService.getScheduledTasks()) {
            scheduledTaskById.put(scheduledTask.getId(), scheduledTask);
        }
        return scheduledTaskById;
    }

    private Map<String, ScheduleEntry> buildScheduleById() {
        Map<String, ScheduleEntry> scheduleById = new LinkedHashMap<>();
        for (ScheduleEntry schedule : scheduleService.getSchedules()) {
            scheduleById.put(schedule.getId(), schedule);
        }
        return scheduleById;
    }

    private Map<String, Goal> buildGoalById() {
        Map<String, Goal> goalById = new LinkedHashMap<>();
        for (Goal goal : loadSessionGoalsForHistory()) {
            goalById.put(goal.getId(), goal);
        }
        return goalById;
    }

    private List<Goal> loadSessionGoalsForHistory() {
        try {
            return autoModeService.getGoals();
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    private Map<String, AutoTask> buildTaskById(Map<String, Goal> goalById) {
        Map<String, AutoTask> taskById = new LinkedHashMap<>();
        for (Goal goal : goalById.values()) {
            for (AutoTask task : goal.getTasks()) {
                taskById.put(task.getId(), task);
            }
        }
        return taskById;
    }

    public record RunSummary(
            String runId,
            String sessionId,
            String channelType,
            String conversationKey,
            String transportChatId,
            String scheduleId,
            String scheduleTargetType,
            String scheduleTargetId,
            String scheduleTargetLabel,
            String scheduledTaskId,
            String scheduledTaskLabel,
            String goalId,
            String goalLabel,
            String taskId,
            String taskLabel,
            String status,
            int messageCount,
            Instant startedAt,
            Instant lastActivityAt) {
    }

    public record RunDetail(
            String runId,
            String sessionId,
            String channelType,
            String conversationKey,
            String transportChatId,
            String scheduleId,
            String scheduleTargetType,
            String scheduleTargetId,
            String scheduleTargetLabel,
            String scheduledTaskId,
            String scheduledTaskLabel,
            String goalId,
            String goalLabel,
            String taskId,
            String taskLabel,
            String status,
            Instant startedAt,
            Instant lastActivityAt,
            List<RunMessage> messages) {
    }

    public record RunMessage(
            String id,
            String role,
            String content,
            Instant timestamp,
            boolean hasToolCalls,
            boolean hasVoice,
            String model,
            String modelTier,
            String skill,
            String status) {
    }

    private static final class RunAggregate {

        private final String runId;
        private final String sessionId;
        private final String channelType;
        private final String conversationKey;
        private final String transportChatId;
        private final List<RunMessage> messages = new ArrayList<>();

        private String scheduleId;
        private String scheduleTargetType;
        private String scheduleTargetId;
        private String scheduleTargetLabel;
        private String scheduledTaskId;
        private String scheduledTaskLabel;
        private String goalId;
        private String goalLabel;
        private String taskId;
        private String taskLabel;
        private Instant startedAt;
        private Instant lastActivityAt;

        private RunAggregate(
                String runId,
                String sessionId,
                String channelType,
                String conversationKey,
                String transportChatId) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.channelType = channelType;
            this.conversationKey = conversationKey;
            this.transportChatId = transportChatId;
        }

        private void captureContext(
                Map<String, Object> metadata,
                Map<String, ScheduleEntry> scheduleById,
                Map<String, Goal> goalById,
                Map<String, AutoTask> taskById,
                Map<String, me.golemcore.bot.domain.model.ScheduledTask> scheduledTaskById) {
            String resolvedScheduleId = AutoRunContextSupport.readMetadataString(metadata,
                    ContextAttributes.AUTO_SCHEDULE_ID);
            if (StringValueSupport.isBlank(scheduleId) && !StringValueSupport.isBlank(resolvedScheduleId)) {
                scheduleId = resolvedScheduleId;
                ScheduleEntry schedule = scheduleById.get(scheduleId);
                if (schedule != null) {
                    scheduleTargetType = schedule.getType().name();
                    scheduleTargetId = schedule.getTargetId();
                    scheduleTargetLabel = resolveScheduleTargetLabel(schedule, goalById, taskById, scheduledTaskById);
                }
            }
            captureScheduledTask(metadata, scheduleById, scheduledTaskById);

            String resolvedGoalId = AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_GOAL_ID);
            if (StringValueSupport.isBlank(goalId) && !StringValueSupport.isBlank(resolvedGoalId)) {
                goalId = resolvedGoalId;
                Goal goal = goalById.get(goalId);
                goalLabel = goal != null ? goal.getTitle() : goalId;
            }

            String resolvedTaskId = AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_TASK_ID);
            if (StringValueSupport.isBlank(taskId) && !StringValueSupport.isBlank(resolvedTaskId)) {
                taskId = resolvedTaskId;
                AutoTask task = taskById.get(taskId);
                taskLabel = task != null ? task.getTitle() : taskId;
            }
        }

        private void addMessage(Message message) {
            Instant timestamp = message.getTimestamp();
            if (startedAt == null || isBefore(timestamp, startedAt)) {
                startedAt = timestamp;
            }
            if (lastActivityAt == null || isAfter(timestamp, lastActivityAt)) {
                lastActivityAt = timestamp;
            }

            Map<String, Object> metadata = message.getMetadata();
            String model = resolveModel(metadata, message.getRole());
            String modelTier = resolveModelTier(metadata, message.getRole());
            String skill = resolveSkill(metadata, message.getRole());
            String status = resolveRunStatus(metadata);

            messages.add(new RunMessage(
                    message.getId(),
                    message.getRole(),
                    message.getContent() != null ? message.getContent() : "",
                    message.getTimestamp(),
                    message.hasToolCalls(),
                    message.hasVoice(),
                    model,
                    modelTier,
                    skill,
                    status));
        }

        private Instant sortInstant() {
            if (lastActivityAt != null) {
                return lastActivityAt;
            }
            return startedAt;
        }

        private RunSummary toSummary() {
            return new RunSummary(
                    runId,
                    sessionId,
                    channelType,
                    conversationKey,
                    transportChatId,
                    scheduleId,
                    scheduleTargetType,
                    scheduleTargetId,
                    scheduleTargetLabel,
                    scheduledTaskId,
                    scheduledTaskLabel,
                    goalId,
                    goalLabel,
                    taskId,
                    taskLabel,
                    resolveStatus(),
                    messages.size(),
                    startedAt,
                    lastActivityAt);
        }

        private RunDetail toDetail() {
            return new RunDetail(
                    runId,
                    sessionId,
                    channelType,
                    conversationKey,
                    transportChatId,
                    scheduleId,
                    scheduleTargetType,
                    scheduleTargetId,
                    scheduleTargetLabel,
                    scheduledTaskId,
                    scheduledTaskLabel,
                    goalId,
                    goalLabel,
                    taskId,
                    taskLabel,
                    resolveStatus(),
                    startedAt,
                    lastActivityAt,
                    List.copyOf(messages));
        }

        private String resolveStatus() {
            String explicit = messages.stream()
                    .map(RunMessage::status)
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (explicit != null) {
                return explicit;
            }
            boolean hasAssistant = messages.stream().anyMatch(message -> "assistant".equals(message.role()));
            if (hasAssistant) {
                return "COMPLETED";
            }
            boolean hasTool = messages.stream().anyMatch(message -> "tool".equals(message.role()));
            if (hasTool) {
                return "TOOL_OUTPUT";
            }
            return "STARTED";
        }

        private void captureScheduledTask(
                Map<String, Object> metadata,
                Map<String, ScheduleEntry> scheduleById,
                Map<String, me.golemcore.bot.domain.model.ScheduledTask> scheduledTaskById) {
            if (!StringValueSupport.isBlank(scheduledTaskId)) {
                return;
            }
            String resolvedScheduledTaskId = AutoRunContextSupport.readMetadataString(metadata,
                    ContextAttributes.AUTO_SCHEDULED_TASK_ID);
            if (StringValueSupport.isBlank(resolvedScheduledTaskId)) {
                ScheduleEntry schedule = !StringValueSupport.isBlank(scheduleId) ? scheduleById.get(scheduleId) : null;
                if (schedule != null && schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
                    resolvedScheduledTaskId = schedule.getTargetId();
                }
            }
            if (StringValueSupport.isBlank(resolvedScheduledTaskId)) {
                return;
            }
            scheduledTaskId = resolvedScheduledTaskId;
            me.golemcore.bot.domain.model.ScheduledTask task = scheduledTaskById.get(resolvedScheduledTaskId);
            scheduledTaskLabel = task != null ? task.getTitle() : resolvedScheduledTaskId;
        }

        private static String resolveModel(Map<String, Object> metadata, String role) {
            if (!shouldExposeTurnMetadata(role) || metadata == null) {
                return null;
            }
            return AutoRunContextSupport.readMetadataString(metadata, "model");
        }

        private static String resolveModelTier(Map<String, Object> metadata, String role) {
            if (!shouldExposeTurnMetadata(role) || metadata == null) {
                return null;
            }
            return AutoRunContextSupport.readMetadataString(metadata, "modelTier");
        }

        private static String resolveSkill(Map<String, Object> metadata, String role) {
            if (!shouldExposeTurnMetadata(role) || metadata == null) {
                return null;
            }
            String activeSkill = AutoRunContextSupport.readMetadataString(metadata,
                    ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
            if (!StringValueSupport.isBlank(activeSkill)) {
                return activeSkill;
            }
            return AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.ACTIVE_SKILL_NAME);
        }

        private static String resolveRunStatus(Map<String, Object> metadata) {
            if (metadata == null) {
                return null;
            }
            return AutoRunContextSupport.readMetadataString(metadata, ContextAttributes.AUTO_RUN_STATUS);
        }

        private static String resolveScheduleTargetLabel(
                ScheduleEntry schedule,
                Map<String, Goal> goalById,
                Map<String, AutoTask> taskById,
                Map<String, me.golemcore.bot.domain.model.ScheduledTask> scheduledTaskById) {
            if (schedule.getType() == ScheduleEntry.ScheduleType.GOAL) {
                Goal goal = goalById.get(schedule.getTargetId());
                return goal != null ? goal.getTitle() : schedule.getTargetId();
            }
            if (schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK) {
                me.golemcore.bot.domain.model.ScheduledTask task = scheduledTaskById.get(schedule.getTargetId());
                return task != null ? task.getTitle() : schedule.getTargetId();
            }
            AutoTask task = taskById.get(schedule.getTargetId());
            return task != null ? task.getTitle() : schedule.getTargetId();
        }

        private static boolean isBefore(Instant candidate, Instant current) {
            if (candidate == null) {
                return false;
            }
            if (current == null) {
                return true;
            }
            return candidate.isBefore(current);
        }

        private static boolean isAfter(Instant candidate, Instant current) {
            if (candidate == null) {
                return false;
            }
            if (current == null) {
                return true;
            }
            return candidate.isAfter(current);
        }

        private static boolean shouldExposeTurnMetadata(String role) {
            return "assistant".equals(role) || "tool".equals(role);
        }
    }
}
