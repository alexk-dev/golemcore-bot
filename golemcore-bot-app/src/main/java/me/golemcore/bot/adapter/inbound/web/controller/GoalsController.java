package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.AutoTask;
import me.golemcore.bot.domain.model.Goal;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.auto.AutoModeService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.StringValueSupport;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * REST endpoints for autonomous goals and tasks, consumed by dashboard UI.
 */
@RestController
@RequestMapping("/api")
public class GoalsController {

    private static final String FEATURE_DISABLED = "Auto mode feature is disabled";

    private final AutoModeService autoModeService;

    public GoalsController(AutoModeService autoModeService) {
        this.autoModeService = autoModeService;
    }

    @GetMapping("/goals")
    public Mono<ResponseEntity<GoalsResponse>> getGoals(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String conversationKey) {
        return Mono.just(ResponseEntity.ok(buildGoalsResponse(resolveGoals(channel, conversationKey))));
    }

    @PostMapping("/goals")
    public Mono<ResponseEntity<GoalDto>> createGoal(@RequestBody CreateGoalRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }

        try {
            Goal goal = autoModeService.createGoal(
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    Boolean.TRUE.equals(request.reflectionTierPriority()));
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toGoalDto(goal)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw badRequest(e.getMessage());
        }
    }

    @PutMapping("/goals/{goalId}")
    public Mono<ResponseEntity<GoalDto>> updateGoal(@PathVariable String goalId,
            @RequestBody UpdateGoalRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }

        try {
            Goal goal = autoModeService.updateGoal(
                    requireId(goalId, "goalId"),
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    request.reflectionTierPriority(),
                    parseGoalStatus(request.status()));
            return Mono.just(ResponseEntity.ok(toGoalDto(goal)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/goals/{goalId}")
    public Mono<ResponseEntity<DeleteGoalResponse>> deleteGoal(@PathVariable String goalId) {
        requireFeatureEnabled();
        try {
            String normalizedGoalId = requireId(goalId, "goalId");
            autoModeService.deleteGoal(normalizedGoalId);
            return Mono.just(ResponseEntity.ok(new DeleteGoalResponse(normalizedGoalId)));
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    @PostMapping("/tasks")
    public Mono<ResponseEntity<TaskDto>> createTask(@RequestBody CreateTaskRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }

        try {
            AutoTask task = autoModeService.createTask(
                    normalizeTaskGoalId(request.goalId()),
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    request.reflectionTierPriority(),
                    parseTaskStatus(request.status(), AutoTask.TaskStatus.PENDING));
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toTaskDto(task, autoModeService.isInboxGoal(
                    autoModeService.findGoalForTask(task.getId()).orElse(null)))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw badRequest(e.getMessage());
        }
    }

    @PutMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<TaskDto>> updateTask(@PathVariable String taskId,
            @RequestBody UpdateTaskRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }

        try {
            AutoTask task = autoModeService.updateTask(
                    requireId(taskId, "taskId"),
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    request.reflectionTierPriority(),
                    parseTaskStatus(request.status(), null));
            boolean standalone = autoModeService.findGoalForTask(task.getId())
                    .map(autoModeService::isInboxGoal)
                    .orElse(false);
            return Mono.just(ResponseEntity.ok(toTaskDto(task, standalone)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<DeleteTaskResponse>> deleteTask(@PathVariable String taskId) {
        requireFeatureEnabled();
        try {
            String normalizedTaskId = requireId(taskId, "taskId");
            autoModeService.deleteTask(normalizedTaskId);
            return Mono.just(ResponseEntity.ok(new DeleteTaskResponse(normalizedTaskId)));
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private List<Goal> resolveGoals(String channel, String conversationKey) {
        boolean hasChannel = !StringValueSupport.isBlank(channel);
        boolean hasConversationKey = !StringValueSupport.isBlank(conversationKey);
        if (!hasChannel && !hasConversationKey) {
            return autoModeService.getGoals();
        }
        if (!hasChannel || !hasConversationKey) {
            throw badRequest("channel and conversationKey must be provided together");
        }

        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(channel, conversationKey);
        if (sessionIdentity == null || !sessionIdentity.isValid()) {
            throw badRequest("Invalid session identity");
        }
        return autoModeService.getGoals(sessionIdentity.asKey());
    }

    private GoalsResponse buildGoalsResponse(List<Goal> allGoals) {
        List<GoalDto> goals = allGoals.stream()
                .filter(goal -> !autoModeService.isInboxGoal(goal))
                .sorted(Comparator.comparing(Goal::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(GoalsController::toGoalDto)
                .toList();

        List<TaskDto> standaloneTasks = allGoals.stream()
                .filter(autoModeService::isInboxGoal)
                .flatMap(goal -> goal.getTasks().stream())
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> toTaskDto(task, true))
                .toList();

        return new GoalsResponse(
                autoModeService.isFeatureEnabled(),
                autoModeService.isAutoModeEnabled(),
                goals,
                standaloneTasks);
    }

    private void requireFeatureEnabled() {
        if (!autoModeService.isFeatureEnabled()) {
            throw badRequest(FEATURE_DISABLED);
        }
    }

    private static GoalDto toGoalDto(Goal goal) {
        List<TaskDto> tasks = goal.getTasks().stream()
                .sorted(Comparator.comparingInt(AutoTask::getOrder))
                .map(task -> toTaskDto(task, false))
                .toList();

        long completedTasks = goal.getCompletedTaskCount();
        int totalTasks = goal.getTasks().size();

        return new GoalDto(
                goal.getId(),
                goal.getTitle(),
                goal.getDescription(),
                goal.getPrompt(),
                goal.getReflectionModelTier(),
                goal.isReflectionTierPriority(),
                goal.getStatus().name(),
                completedTasks,
                totalTasks,
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
                task.getReflectionModelTier(),
                task.isReflectionTierPriority(),
                task.getStatus().name(),
                task.getOrder(),
                task.getConsecutiveFailureCount(),
                task.isReflectionRequired(),
                task.getReflectionStrategy(),
                standalone);
    }

    private static Goal.GoalStatus parseGoalStatus(String value) {
        if (StringValueSupport.isBlank(value)) {
            throw badRequest("status is required");
        }
        try {
            return Goal.GoalStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("Unsupported goal status: " + value);
        }
    }

    private static AutoTask.TaskStatus parseTaskStatus(String value, AutoTask.TaskStatus fallback) {
        if (StringValueSupport.isBlank(value)) {
            return fallback;
        }
        try {
            return AutoTask.TaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("Unsupported task status: " + value);
        }
    }

    private static String requireId(String value, String fieldName) {
        if (StringValueSupport.isBlank(value)) {
            throw badRequest(fieldName + " is required");
        }
        return value.trim();
    }

    private static String normalizeTaskGoalId(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeOptionalReflectionModelTier(String value) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(value);
        if (normalizedTier == null) {
            return null;
        }
        if (!ModelTierCatalog.isExplicitSelectableTier(normalizedTier)) {
            throw badRequest("reflectionModelTier must be a known tier id");
        }
        return normalizedTier;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record GoalsResponse(
            boolean featureEnabled,
            boolean autoModeEnabled,
            List<GoalDto> goals,
            List<TaskDto> standaloneTasks) {
    }

    public record GoalDto(
            String id,
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            boolean reflectionTierPriority,
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
            String reflectionModelTier,
            boolean reflectionTierPriority,
            String status,
            int order,
            int consecutiveFailureCount,
            boolean reflectionRequired,
            String reflectionStrategy,
            boolean standalone) {
    }

    public record CreateGoalRequest(String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority) {
    }

    public record UpdateGoalRequest(String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, String status) {
    }

    public record DeleteGoalResponse(String goalId) {
    }

    public record CreateTaskRequest(String goalId, String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, String status) {
    }

    public record UpdateTaskRequest(String title, String description, String prompt,
            String reflectionModelTier, Boolean reflectionTierPriority, String status) {
    }

    public record DeleteTaskResponse(String taskId) {
    }
}
