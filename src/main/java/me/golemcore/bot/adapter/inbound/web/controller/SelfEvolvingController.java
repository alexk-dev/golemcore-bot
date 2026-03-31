package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCampaignDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.service.BenchmarkLabService;
import me.golemcore.bot.domain.service.PromotionWorkflowService;
import me.golemcore.bot.domain.service.SelfEvolvingProjectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Readonly SelfEvolving endpoints for the local dashboard.
 */
@RestController
@RequestMapping("/api/self-evolving")
@RequiredArgsConstructor
public class SelfEvolvingController {

    private final SelfEvolvingProjectionService projectionService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final BenchmarkLabService benchmarkLabService;

    @GetMapping("/runs")
    public Mono<ResponseEntity<List<SelfEvolvingRunSummaryDto>>> listRuns() {
        return Mono.just(ResponseEntity.ok(projectionService.listRuns()));
    }

    @GetMapping("/runs/{runId}")
    public Mono<ResponseEntity<SelfEvolvingRunDetailDto>> getRun(@PathVariable String runId) {
        SelfEvolvingRunDetailDto runDetail = projectionService.getRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        return Mono.just(ResponseEntity.ok(runDetail));
    }

    @GetMapping("/candidates")
    public Mono<ResponseEntity<List<SelfEvolvingCandidateDto>>> listCandidates() {
        return Mono.just(ResponseEntity.ok(projectionService.listCandidates()));
    }

    @PostMapping("/candidates/{candidateId}/promotion")
    public Mono<ResponseEntity<PromotionDecision>> planPromotion(@PathVariable String candidateId) {
        return Mono.just(ResponseEntity.ok(promotionWorkflowService.planPromotion(candidateId)));
    }

    @GetMapping("/benchmarks/campaigns")
    public Mono<ResponseEntity<List<SelfEvolvingCampaignDto>>> listCampaigns() {
        return Mono.just(ResponseEntity.ok(projectionService.listCampaigns()));
    }

    @PostMapping("/benchmarks/regression/{runId}")
    public Mono<ResponseEntity<SelfEvolvingCampaignDto>> createRegressionCampaign(@PathVariable String runId) {
        BenchmarkCampaign campaign = benchmarkLabService.createRegressionCampaign(runId);
        return Mono.just(ResponseEntity.ok(toCampaignDto(campaign)));
    }

    private SelfEvolvingCampaignDto toCampaignDto(BenchmarkCampaign campaign) {
        return SelfEvolvingCampaignDto.builder()
                .id(campaign.getId())
                .suiteId(campaign.getSuiteId())
                .baselineBundleId(campaign.getBaselineBundleId())
                .candidateBundleId(campaign.getCandidateBundleId())
                .status(campaign.getStatus())
                .startedAt(campaign.getStartedAt() != null ? campaign.getStartedAt().toString() : null)
                .completedAt(campaign.getCompletedAt() != null ? campaign.getCompletedAt().toString() : null)
                .runIds(campaign.getRunIds())
                .build();
    }
}
