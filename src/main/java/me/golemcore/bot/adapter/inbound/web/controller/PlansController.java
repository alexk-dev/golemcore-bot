package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.Plan;
import me.golemcore.bot.domain.service.PlanExecutionService;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Plan mode control endpoints used by dashboard chat side-panel.
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlansController {

    private static final String PLAN_FEATURE_DISABLED = "Plan mode feature is disabled";

    private final PlanService planService;
    private final PlanExecutionService planExecutionService;

    @GetMapping
    public Mono<ResponseEntity<PlanControlStateResponse>> getState() {
        return Mono.just(ResponseEntity.ok(buildStateResponse()));
    }

    @PostMapping("/mode/on")
    public Mono<ResponseEntity<PlanControlStateResponse>> enablePlanMode(
            @RequestBody(required = false) PlanModeOnRequest request) {
        requireFeatureEnabled();
        if (request == null || request.chatId() == null || request.chatId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chatId is required");
        }

        try {
            String modelTier = request.modelTier();
            if (modelTier != null && modelTier.isBlank()) {
                modelTier = null;
            }
            if (!planService.isPlanModeActive()) {
                planService.activatePlanMode(request.chatId(), modelTier);
            }
            return Mono.just(ResponseEntity.ok(buildStateResponse()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/mode/off")
    public Mono<ResponseEntity<PlanControlStateResponse>> disablePlanMode() {
        requireFeatureEnabled();
        if (planService.isPlanModeActive()) {
            planService.deactivatePlanMode();
        }
        return Mono.just(ResponseEntity.ok(buildStateResponse()));
    }

    @PostMapping("/mode/done")
    public Mono<ResponseEntity<PlanControlStateResponse>> donePlanMode() {
        requireFeatureEnabled();
        if (planService.isPlanModeActive()) {
            planService.deactivatePlanMode();
        }
        return Mono.just(ResponseEntity.ok(buildStateResponse()));
    }

    @PostMapping("/{planId}/approve")
    public Mono<ResponseEntity<PlanControlStateResponse>> approvePlan(@PathVariable String planId) {
        requireFeatureEnabled();
        try {
            planService.approvePlan(planId);
            planExecutionService.executePlan(planId);
            return Mono.just(ResponseEntity.ok(buildStateResponse()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/{planId}/cancel")
    public Mono<ResponseEntity<PlanControlStateResponse>> cancelPlan(@PathVariable String planId) {
        requireFeatureEnabled();
        try {
            planService.cancelPlan(planId);
            return Mono.just(ResponseEntity.ok(buildStateResponse()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/{planId}/resume")
    public Mono<ResponseEntity<PlanControlStateResponse>> resumePlan(@PathVariable String planId) {
        requireFeatureEnabled();
        try {
            planExecutionService.resumePlan(planId);
            return Mono.just(ResponseEntity.ok(buildStateResponse()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private void requireFeatureEnabled() {
        if (!planService.isFeatureEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PLAN_FEATURE_DISABLED);
        }
    }

    private PlanControlStateResponse buildStateResponse() {
        boolean featureEnabled = planService.isFeatureEnabled();
        if (!featureEnabled) {
            return new PlanControlStateResponse(false, false, null, List.of());
        }

        String activePlanId = planService.getActivePlanId();
        List<PlanSummaryDto> plans = planService.getPlans().stream()
                .sorted(Comparator.comparing(PlansController::updatedAtSafe).reversed())
                .map(plan -> toDto(plan, activePlanId))
                .toList();

        return new PlanControlStateResponse(
                true,
                planService.isPlanModeActive(),
                activePlanId,
                plans);
    }

    private static Instant updatedAtSafe(Plan plan) {
        Instant updatedAt = plan.getUpdatedAt();
        if (updatedAt != null) {
            return updatedAt;
        }
        Instant createdAt = plan.getCreatedAt();
        return createdAt != null ? createdAt : Instant.EPOCH;
    }

    private static PlanSummaryDto toDto(Plan plan, String activePlanId) {
        int stepCount = plan.getSteps() != null ? plan.getSteps().size() : 0;
        return new PlanSummaryDto(
                plan.getId(),
                plan.getTitle(),
                plan.getStatus().name(),
                plan.getModelTier(),
                plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null,
                plan.getUpdatedAt() != null ? plan.getUpdatedAt().toString() : null,
                stepCount,
                plan.getCompletedStepCount(),
                plan.getFailedStepCount(),
                plan.getId() != null && plan.getId().equals(activePlanId));
    }

    public record PlanModeOnRequest(String chatId, String modelTier) {
    }

    public record PlanControlStateResponse(
            boolean featureEnabled,
            boolean planModeActive,
            String activePlanId,
            List<PlanSummaryDto> plans) {
    }

    public record PlanSummaryDto(
            String id,
            String title,
            String status,
            String modelTier,
            String createdAt,
            String updatedAt,
            int stepCount,
            long completedStepCount,
            long failedStepCount,
            boolean active) {
    }
}
