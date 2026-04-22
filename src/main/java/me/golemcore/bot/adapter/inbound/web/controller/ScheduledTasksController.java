package me.golemcore.bot.adapter.inbound.web.controller;

import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.service.AutoModeService;
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
@RequiredArgsConstructor
public class ScheduledTasksController {

    private static final String FEATURE_DISABLED = "Auto mode feature is disabled";

    private final AutoModeService autoModeService;

    @GetMapping
    public Mono<ResponseEntity<ScheduledTasksResponse>> listScheduledTasks() {
        List<ScheduledTaskDto> scheduledTasks = autoModeService.getScheduledTasks().stream()
                .sorted(Comparator.comparing(ScheduledTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
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
            ScheduledTask task = autoModeService.createScheduledTask(
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    Boolean.TRUE.equals(request.reflectionTierPriority()));
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
            ScheduledTask task = autoModeService.updateScheduledTask(
                    requireId(scheduledTaskId, "scheduledTaskId"),
                    request.title(),
                    request.description(),
                    request.prompt(),
                    normalizeOptionalReflectionModelTier(request.reflectionModelTier()),
                    request.reflectionTierPriority());
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
            autoModeService.deleteScheduledTask(normalizedId);
            return Mono.just(ResponseEntity.ok(new DeleteScheduledTaskResponse(normalizedId)));
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    private void requireFeatureEnabled() {
        if (!autoModeService.isFeatureEnabled()) {
            throw badRequest(FEATURE_DISABLED);
        }
    }

    private ScheduledTaskDto toDto(ScheduledTask task) {
        return new ScheduledTaskDto(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getPrompt(),
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

    private static String requireId(String value, String fieldName) {
        if (StringValueSupport.isBlank(value)) {
            throw badRequest(fieldName + " is required");
        }
        return value.trim();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
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
            String reflectionModelTier,
            boolean reflectionTierPriority,
            String legacySourceType,
            String legacySourceId) {
    }

    public record CreateScheduledTaskRequest(
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority) {
    }

    public record UpdateScheduledTaskRequest(
            String title,
            String description,
            String prompt,
            String reflectionModelTier,
            Boolean reflectionTierPriority) {
    }

    public record DeleteScheduledTaskResponse(String scheduledTaskId) {
    }
}
