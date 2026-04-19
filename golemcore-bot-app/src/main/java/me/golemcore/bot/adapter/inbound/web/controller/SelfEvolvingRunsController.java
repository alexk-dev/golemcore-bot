package me.golemcore.bot.adapter.inbound.web.controller;

import static me.golemcore.bot.adapter.inbound.web.controller.SelfEvolvingControllerSupport.blocking;

import java.util.List;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingPromotionDecisionDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * SelfEvolving runs and candidates endpoints (including promotion planning).
 */
@RestController
@RequestMapping("/api/self-evolving")
public class SelfEvolvingRunsController {

    private final SelfEvolvingProjectionService projectionService;
    private final PromotionWorkflowService promotionWorkflowService;

    public SelfEvolvingRunsController(SelfEvolvingProjectionService projectionService,
            PromotionWorkflowService promotionWorkflowService) {
        this.projectionService = projectionService;
        this.promotionWorkflowService = promotionWorkflowService;
    }

    @GetMapping("/runs")
    public Mono<ResponseEntity<List<SelfEvolvingRunSummaryDto>>> listRuns() {
        return blocking(() -> ResponseEntity.ok(projectionService.listRuns()));
    }

    @GetMapping("/runs/{runId}")
    public Mono<ResponseEntity<SelfEvolvingRunDetailDto>> getRun(@PathVariable String runId) {
        return blocking(() -> {
            SelfEvolvingRunDetailDto runDetail = projectionService.getRun(runId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
            return ResponseEntity.ok(runDetail);
        });
    }

    @GetMapping("/candidates")
    public Mono<ResponseEntity<List<SelfEvolvingCandidateDto>>> listCandidates() {
        return blocking(() -> ResponseEntity.ok(projectionService.listCandidates()));
    }

    @PostMapping("/candidates/{candidateId}/promotion")
    public Mono<ResponseEntity<SelfEvolvingPromotionDecisionDto>> planPromotion(@PathVariable String candidateId) {
        return blocking(
                () -> ResponseEntity.ok(toPromotionDecisionDto(promotionWorkflowService.planPromotion(candidateId))));
    }

    private SelfEvolvingPromotionDecisionDto toPromotionDecisionDto(PromotionDecision decision) {
        return SelfEvolvingPromotionDecisionDto.builder()
                .id(decision.getId())
                .candidateId(decision.getCandidateId())
                .bundleId(decision.getBundleId())
                .state(decision.getState())
                .fromState(decision.getFromState())
                .toState(decision.getToState())
                .mode(decision.getMode())
                .approvalRequestId(decision.getApprovalRequestId())
                .actorId(decision.getActorId())
                .reason(decision.getReason())
                .decidedAt(decision.getDecidedAt() != null ? decision.getDecidedAt().toString() : null)
                .build();
    }
}
