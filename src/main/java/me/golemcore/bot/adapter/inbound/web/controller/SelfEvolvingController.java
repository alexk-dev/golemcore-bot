package me.golemcore.bot.adapter.inbound.web.controller;

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCampaignDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCompareOptionsDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactRevisionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactTransitionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactWorkspaceSummaryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
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
import org.springframework.web.bind.annotation.RequestParam;
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
@Slf4j
public class SelfEvolvingController {

    private final SelfEvolvingProjectionService projectionService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final BenchmarkLabService benchmarkLabService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;

    public SelfEvolvingController(SelfEvolvingProjectionService projectionService,
            PromotionWorkflowService promotionWorkflowService,
            BenchmarkLabService benchmarkLabService,
            HiveEventBatchPublisher hiveEventBatchPublisher) {
        this.projectionService = projectionService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.benchmarkLabService = benchmarkLabService;
        this.hiveEventBatchPublisher = hiveEventBatchPublisher;
    }

    SelfEvolvingController(SelfEvolvingProjectionService projectionService,
            PromotionWorkflowService promotionWorkflowService,
            BenchmarkLabService benchmarkLabService) {
        this(projectionService, promotionWorkflowService, benchmarkLabService, null);
    }

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

    @GetMapping("/artifacts")
    public Mono<ResponseEntity<List<SelfEvolvingArtifactCatalogEntryDto>>> listArtifacts(
            @RequestParam(required = false) String artifactType,
            @RequestParam(required = false) String artifactSubtype,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(required = false) String rolloutStage,
            @RequestParam(required = false) Boolean hasPendingApproval,
            @RequestParam(required = false) Boolean hasRegression,
            @RequestParam(required = false) Boolean benchmarked,
            @RequestParam(name = "q", required = false) String query) {
        return Mono.just(ResponseEntity.ok(projectionService.listArtifacts(
                artifactType,
                artifactSubtype,
                lifecycleState,
                rolloutStage,
                hasPendingApproval,
                hasRegression,
                benchmarked,
                query)));
    }

    @GetMapping("/artifacts/{artifactStreamId}")
    public Mono<ResponseEntity<SelfEvolvingArtifactWorkspaceSummaryDto>> getArtifactWorkspaceSummary(
            @PathVariable String artifactStreamId) {
        SelfEvolvingArtifactWorkspaceSummaryDto summary = projectionService
                .getArtifactWorkspaceSummary(artifactStreamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(summary));
    }

    @GetMapping("/artifacts/{artifactStreamId}/lineage")
    public Mono<ResponseEntity<SelfEvolvingArtifactLineageDto>> getArtifactLineage(
            @PathVariable String artifactStreamId) {
        SelfEvolvingArtifactLineageDto lineage = projectionService.getArtifactLineage(artifactStreamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(lineage));
    }

    @GetMapping("/artifacts/{artifactStreamId}/diff")
    public Mono<ResponseEntity<SelfEvolvingArtifactRevisionDiffDto>> getArtifactRevisionDiff(
            @PathVariable String artifactStreamId,
            @RequestParam(required = false) String fromRevisionId,
            @RequestParam(required = false) String toRevisionId) {
        requireQueryParam("fromRevisionId", fromRevisionId);
        requireQueryParam("toRevisionId", toRevisionId);
        SelfEvolvingArtifactRevisionDiffDto diff = projectionService
                .getArtifactRevisionDiff(artifactStreamId, fromRevisionId, toRevisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(diff));
    }

    @GetMapping("/artifacts/{artifactStreamId}/transition-diff")
    public Mono<ResponseEntity<SelfEvolvingArtifactTransitionDiffDto>> getArtifactTransitionDiff(
            @PathVariable String artifactStreamId,
            @RequestParam(required = false) String fromNodeId,
            @RequestParam(required = false) String toNodeId) {
        requireQueryParam("fromNodeId", fromNodeId);
        requireQueryParam("toNodeId", toNodeId);
        SelfEvolvingArtifactTransitionDiffDto diff = projectionService
                .getArtifactTransitionDiff(artifactStreamId, fromNodeId, toNodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(diff));
    }

    @GetMapping("/artifacts/{artifactStreamId}/evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getArtifactRevisionEvidence(
            @PathVariable String artifactStreamId,
            @RequestParam(required = false) String revisionId) {
        requireQueryParam("revisionId", revisionId);
        SelfEvolvingArtifactEvidenceDto evidence = projectionService
                .getArtifactRevisionEvidence(artifactStreamId, revisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(evidence));
    }

    @GetMapping("/artifacts/{artifactStreamId}/compare-evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getArtifactCompareEvidence(
            @PathVariable String artifactStreamId,
            @RequestParam(required = false) String fromRevisionId,
            @RequestParam(required = false) String toRevisionId) {
        requireQueryParam("fromRevisionId", fromRevisionId);
        requireQueryParam("toRevisionId", toRevisionId);
        SelfEvolvingArtifactEvidenceDto evidence = projectionService
                .getArtifactCompareEvidence(artifactStreamId, fromRevisionId, toRevisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(evidence));
    }

    @GetMapping("/artifacts/{artifactStreamId}/transition-evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getArtifactTransitionEvidence(
            @PathVariable String artifactStreamId,
            @RequestParam(required = false) String fromNodeId,
            @RequestParam(required = false) String toNodeId) {
        requireQueryParam("fromNodeId", fromNodeId);
        requireQueryParam("toNodeId", toNodeId);
        SelfEvolvingArtifactEvidenceDto evidence = projectionService
                .getArtifactTransitionEvidence(artifactStreamId, fromNodeId, toNodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(evidence));
    }

    @GetMapping("/artifacts/{artifactStreamId}/compare-options")
    public Mono<ResponseEntity<SelfEvolvingArtifactCompareOptionsDto>> getArtifactCompareOptions(
            @PathVariable String artifactStreamId) {
        SelfEvolvingArtifactCompareOptionsDto compareOptions = projectionService
                .getArtifactCompareOptions(artifactStreamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
        return Mono.just(ResponseEntity.ok(compareOptions));
    }

    @GetMapping("/tactics")
    public Mono<ResponseEntity<List<SelfEvolvingTacticDto>>> listTactics() {
        List<SelfEvolvingTacticDto> tactics = projectionService.listTactics();
        publishHiveTacticCatalog(tactics);
        return Mono.just(ResponseEntity.ok(tactics));
    }

    @GetMapping("/tactics/search")
    public Mono<ResponseEntity<SelfEvolvingTacticSearchResponseDto>> searchTactics(
            @RequestParam(name = "q", required = false) String query) {
        SelfEvolvingTacticSearchResponseDto response = projectionService.searchTactics(query);
        publishHiveTacticSearch(response);
        return Mono.just(ResponseEntity.ok(response));
    }

    @GetMapping("/tactics/{tacticId}")
    public Mono<ResponseEntity<SelfEvolvingTacticDto>> getTactic(@PathVariable String tacticId) {
        SelfEvolvingTacticDto tactic = projectionService.getTactic(tacticId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
        return Mono.just(ResponseEntity.ok(tactic));
    }

    @GetMapping("/tactics/{tacticId}/explanation")
    public Mono<ResponseEntity<SelfEvolvingTacticSearchExplanationDto>> getTacticExplanation(
            @PathVariable String tacticId,
            @RequestParam(name = "q", required = false) String query) {
        SelfEvolvingTacticSearchExplanationDto explanation = projectionService.getTacticExplanation(tacticId, query)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
        return Mono.just(ResponseEntity.ok(explanation));
    }

    @GetMapping("/tactics/{tacticId}/lineage")
    public Mono<ResponseEntity<SelfEvolvingArtifactLineageDto>> getTacticLineage(@PathVariable String tacticId) {
        SelfEvolvingArtifactLineageDto lineage = projectionService.getTacticLineage(tacticId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
        return Mono.just(ResponseEntity.ok(lineage));
    }

    @GetMapping("/tactics/{tacticId}/evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getTacticEvidence(@PathVariable String tacticId) {
        SelfEvolvingArtifactEvidenceDto evidence = projectionService.getTacticEvidence(tacticId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
        return Mono.just(ResponseEntity.ok(evidence));
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
        publishHiveCampaignProjection(campaign);
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

    private void publishHiveCampaignProjection(BenchmarkCampaign campaign) {
        if (hiveEventBatchPublisher == null || campaign == null) {
            return;
        }
        try {
            hiveEventBatchPublisher.publishSelfEvolvingCampaignProjection(null, campaign);
        } catch (RuntimeException exception) {
            log.debug("[Hive] Skipping SelfEvolving campaign projection publish: {}", exception.getMessage());
        }
    }

    private void publishHiveTacticCatalog(List<SelfEvolvingTacticDto> tactics) {
        if (hiveEventBatchPublisher == null || tactics == null || tactics.isEmpty()) {
            return;
        }
        try {
            hiveEventBatchPublisher.publishSelfEvolvingTacticCatalogProjection(tactics);
        } catch (RuntimeException exception) {
            log.debug("[Hive] Skipping SelfEvolving tactic catalog publish: {}", exception.getMessage());
        }
    }

    private void publishHiveTacticSearch(SelfEvolvingTacticSearchResponseDto response) {
        if (hiveEventBatchPublisher == null || response == null) {
            return;
        }
        try {
            hiveEventBatchPublisher.publishSelfEvolvingTacticSearchProjection(response);
        } catch (RuntimeException exception) {
            log.debug("[Hive] Skipping SelfEvolving tactic search publish: {}", exception.getMessage());
        }
    }

    private void requireQueryParam(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required query param: " + name);
        }
    }
}
