package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.service.ConversationKeyValidator;
import me.golemcore.bot.domain.service.PlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Ephemeral plan mode control endpoints used by the dashboard chat side-panel.
 */
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlansController {

    private static final String CHANNEL_TYPE_WEB = "web";

    private final PlanService planService;

    @GetMapping
    public Mono<ResponseEntity<PlanControlStateResponse>> getState(@RequestParam String sessionId) {
        return Mono.just(ResponseEntity.ok(buildStateResponse(resolveWebSessionIdentity(sessionId))));
    }

    @PostMapping("/mode/on")
    public Mono<ResponseEntity<PlanControlStateResponse>> enablePlanMode(
            @RequestBody(required = false) PlanModeOnRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        SessionIdentity sessionIdentity = resolveWebSessionIdentity(request.sessionId());
        if (!planService.isPlanModeActive(sessionIdentity)) {
            planService.activatePlanMode(sessionIdentity, sessionIdentity.conversationKey(), null);
        }
        return Mono.just(ResponseEntity.ok(buildStateResponse(sessionIdentity)));
    }

    @PostMapping("/mode/off")
    public Mono<ResponseEntity<PlanControlStateResponse>> disablePlanMode(@RequestParam String sessionId) {
        SessionIdentity sessionIdentity = resolveWebSessionIdentity(sessionId);
        planService.deactivatePlanMode(sessionIdentity);
        return Mono.just(ResponseEntity.ok(buildStateResponse(sessionIdentity)));
    }

    @PostMapping("/mode/done")
    public Mono<ResponseEntity<PlanControlStateResponse>> donePlanMode(@RequestParam String sessionId) {
        SessionIdentity sessionIdentity = resolveWebSessionIdentity(sessionId);
        planService.completePlanMode(sessionIdentity);
        return Mono.just(ResponseEntity.ok(buildStateResponse(sessionIdentity)));
    }

    Mono<ResponseEntity<PlanControlStateResponse>> getState() {
        return Mono.just(ResponseEntity.ok(buildLegacyStateResponse()));
    }

    Mono<ResponseEntity<PlanControlStateResponse>> disablePlanMode() {
        planService.deactivatePlanMode();
        return Mono.just(ResponseEntity.ok(buildLegacyStateResponse()));
    }

    Mono<ResponseEntity<PlanControlStateResponse>> donePlanMode() {
        planService.completePlanMode();
        return Mono.just(ResponseEntity.ok(buildLegacyStateResponse()));
    }

    private PlanControlStateResponse buildStateResponse(SessionIdentity sessionIdentity) {
        return new PlanControlStateResponse(
                true,
                sessionIdentity.conversationKey(),
                planService.isPlanModeActive(sessionIdentity),
                planService.getActivePlanId(sessionIdentity),
                List.of());
    }

    private PlanControlStateResponse buildLegacyStateResponse() {
        return new PlanControlStateResponse(true, null, planService.isPlanModeActive(), planService.getActivePlanId(),
                List.of());
    }

    private SessionIdentity resolveWebSessionIdentity(String sessionId) {
        try {
            String normalizedSessionId = ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(sessionId);
            return new SessionIdentity(CHANNEL_TYPE_WEB, normalizedSessionId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sessionId", e);
        }
    }

    public record PlanModeOnRequest(String sessionId) {
    }

    public record PlanControlStateResponse(
            boolean featureEnabled,
            String sessionId,
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
