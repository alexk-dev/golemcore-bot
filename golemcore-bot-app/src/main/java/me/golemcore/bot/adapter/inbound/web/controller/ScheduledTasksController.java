package me.golemcore.bot.adapter.inbound.web.controller;

import static me.golemcore.bot.adapter.inbound.web.controller.SelfEvolvingControllerSupport.blocking;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import me.golemcore.bot.auto.ScheduleDeliveryContext;
import me.golemcore.bot.auto.ScheduledRunExecutor;
import me.golemcore.bot.auto.ScheduledRunOutcome;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.ScheduleService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTasksController {

    private static final String FEATURE_DISABLED = "Auto mode feature is disabled";

    private final AutoModeService autoModeService;
    private final ScheduleService scheduleService;
    private final ScheduledRunExecutor scheduledRunExecutor;
    private final RuntimeConfigService runtimeConfigService;

    public ScheduledTasksController(
            AutoModeService autoModeService,
            ScheduleService scheduleService,
            ScheduledRunExecutor scheduledRunExecutor,
            RuntimeConfigService runtimeConfigService) {
        this.autoModeService = autoModeService;
        this.scheduleService = scheduleService;
        this.scheduledRunExecutor = scheduledRunExecutor;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping
    public Mono<ResponseEntity<ScheduledTasksResponse>> listScheduledTasks() {
        List<ScheduledTaskDto> scheduledTasks = autoModeService.getScheduledTasks().stream()
                .sorted(Comparator.comparing(ScheduledTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toDto)
                .toList();
        return Mono.just(ResponseEntity.ok(new ScheduledTasksResponse(
                autoModeService.isFeatureEnabled(),
                autoModeService.isAutoModeEnabled(),
                scheduledTasks)));
    }

    @PostMapping
    public Mono<ResponseEntity<ScheduledTaskDto>> createScheduledTask(@RequestBody CreateScheduledTaskRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }
        try {
            ScheduledTask.ExecutionMode executionMode = normalizeExecutionMode(request.executionMode());
            requireShellToolEnabledFor(executionMode);
            ScheduledTask task = autoModeService.createScheduledTask(
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    Boolean.TRUE.equals(request.reflectionTierPriority()),
                    executionMode,
                    request.shellCommand(),
                    request.shellWorkingDirectory());
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(toDto(task)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @PutMapping("/{scheduledTaskId}")
    public Mono<ResponseEntity<ScheduledTaskDto>> updateScheduledTask(@PathVariable String scheduledTaskId,
            @RequestBody UpdateScheduledTaskRequest request) {
        requireFeatureEnabled();
        if (request == null) {
            throw badRequest("Request body is required");
        }
        try {
            ScheduledTask.ExecutionMode executionMode = normalizeOptionalExecutionMode(request.executionMode());
            requireShellToolEnabledFor(executionMode);
            ScheduledTask task = autoModeService.updateScheduledTask(
                    requireId(scheduledTaskId, "scheduledTaskId"),
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    request.reflectionTierPriority(),
                    executionMode,
                    request.shellCommand(),
                    request.shellWorkingDirectory());
            return Mono.just(ResponseEntity.ok(toDto(task)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @DeleteMapping("/{scheduledTaskId}")
    public Mono<ResponseEntity<DeleteScheduledTaskResponse>> deleteScheduledTask(@PathVariable String scheduledTaskId) {
        requireFeatureEnabled();
        try {
            String normalizedId = requireId(scheduledTaskId, "scheduledTaskId");
            if (hasLinkedSchedules(normalizedId)) {
                throw badRequest("Scheduled task has linked schedules; delete schedules before deleting the task");
            }
            autoModeService.deleteScheduledTask(normalizedId);
            return Mono.just(ResponseEntity.ok(new DeleteScheduledTaskResponse(normalizedId)));
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/run")
    public Mono<ResponseEntity<RunScheduledTaskResponse>> runScheduledTaskNow(@PathVariable String scheduledTaskId) {
        return blocking(() -> {
            requireFeatureEnabled();
            try {
                String normalizedId = requireId(scheduledTaskId, "scheduledTaskId");
                ScheduledTask scheduledTask = autoModeService.getScheduledTask(normalizedId)
                        .orElseThrow(() -> badRequest("Scheduled task not found: " + normalizedId));
                requireShellToolEnabledFor(scheduledTask.getExecutionModeOrDefault());
                if (scheduleService.isScheduledTaskBlocked(normalizedId)) {
                    throw conflict("Scheduled task is blocked by an active retry window: " + normalizedId);
                }
                ScheduledRunOutcome outcome = scheduledRunExecutor.executeSchedule(
                        buildManualRunSchedule(normalizedId),
                        ScheduleDeliveryContext.auto(),
                        runtimeConfigService.getAutoTaskTimeLimitMinutes());
                if (outcome == ScheduledRunOutcome.SKIPPED_TASK_BUSY) {
                    throw conflict("Scheduled task is already running: " + normalizedId);
                }
                return ResponseEntity.ok(new RunScheduledTaskResponse(normalizedId, outcome.name()));
            } catch (IllegalArgumentException exception) {
                throw badRequest(exception.getMessage());
            }
        });
    }

    private boolean hasLinkedSchedules(String scheduledTaskId) {
        return scheduleService.findSchedulesForTarget(scheduledTaskId).stream()
                .anyMatch(schedule -> schedule.getType() == ScheduleEntry.ScheduleType.SCHEDULED_TASK);
    }

    private void requireFeatureEnabled() {
        if (!autoModeService.isFeatureEnabled()) {
            throw badRequest(FEATURE_DISABLED);
        }
    }

    private ScheduleEntry buildManualRunSchedule(String scheduledTaskId) {
        Instant now = Instant.now();
        return ScheduleEntry.builder()
                .id("manual-scheduled-task-" + UUID.randomUUID().toString().substring(0, 8))
                .type(ScheduleEntry.ScheduleType.SCHEDULED_TASK)
                .targetId(scheduledTaskId)
                .enabled(true)
                .clearContextBeforeRun(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ScheduledTaskDto toDto(ScheduledTask task) {
        return new ScheduledTaskDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getPrompt(),
                task.getExecutionModeOrDefault().name(),
                task.getShellCommand(),
                task.getShellWorkingDirectory(),
                task.getReflectionModelTier(),
                task.isReflectionTierPriority(),
                task.getLegacySourceType(),
                task.getLegacySourceId());
    }

    private String normalizeOptionalReflectionModelTier(String reflectionModelTier) {
        String normalizedTier = ModelTierCatalog.normalizeTierId(reflectionModelTier);
        if (normalizedTier == null) {
            return null;
        }
        if (!ModelTierCatalog.isExplicitSelectableTier(normalizedTier)) {
            throw new IllegalArgumentException("reflectionModelTier must be a known tier id");
        }
        return normalizedTier;
    }

    private ScheduledTask.ExecutionMode normalizeExecutionMode(String executionMode) {
        if (StringValueSupport.isBlank(executionMode)) {
            return ScheduledTask.ExecutionMode.AGENT_PROMPT;
        }
        return parseExecutionMode(executionMode);
    }

    private ScheduledTask.ExecutionMode normalizeOptionalExecutionMode(String executionMode) {
        if (StringValueSupport.isBlank(executionMode)) {
            return null;
        }
        return parseExecutionMode(executionMode);
    }

    private ScheduledTask.ExecutionMode parseExecutionMode(String executionMode) {
        String normalized = executionMode.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        try {
            return ScheduledTask.ExecutionMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("executionMode must be AGENT_PROMPT or SHELL_COMMAND");
        }
    }

    private void requireShellToolEnabledFor(ScheduledTask.ExecutionMode executionMode) {
        if (executionMode == ScheduledTask.ExecutionMode.SHELL_COMMAND && !runtimeConfigService.isShellEnabled()) {
            throw badRequest("Shell tool is disabled; enable shell before using SHELL_COMMAND tasks");
        }
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

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record ScheduledTasksResponse(
            boolean featureEnabled,
            boolean autoModeEnabled,
            List<ScheduledTaskDto> scheduledTasks) {
    }

    public record ScheduledTaskDto(
            String id,
            String title,
            String description,
            String prompt,
            String executionMode,
            String shellCommand,
            String shellWorkingDirectory,
            String reflectionModelTier,
            boolean reflectionTierPriority,
            String legacySourceType,
            String legacySourceId) {
    }

    public record CreateScheduledTaskRequest(
            String title,
            String description,
            String prompt,
            String executionMode,
            String shellCommand,
            String shellWorkingDirectory,
            String reflectionModelTier,
            Boolean reflectionTierPriority) {
    }

    public record UpdateScheduledTaskRequest(
            String title,
            String description,
            String prompt,
            String executionMode,
            String shellCommand,
            String shellWorkingDirectory,
            String reflectionModelTier,
            Boolean reflectionTierPriority) {
    }

    public record DeleteScheduledTaskResponse(String scheduledTaskId) {
    }

    public record RunScheduledTaskResponse(String scheduledTaskId, String outcome) {
    }
}
