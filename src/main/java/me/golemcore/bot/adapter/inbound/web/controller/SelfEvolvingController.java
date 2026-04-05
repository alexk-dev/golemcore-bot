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
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingPromotionDecisionDto;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.tactic.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.domain.selfevolving.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * SelfEvolving endpoints for the local dashboard (read and write).
 */
@RestController
@RequestMapping("/api/self-evolving")
@Slf4j
public class SelfEvolvingController {

    public record TacticEmbeddingInstallRequest(String model) {
    }

    private final SelfEvolvingProjectionService projectionService;
    private final PromotionWorkflowService promotionWorkflowService;
    private final BenchmarkLabService benchmarkLabService;
    private final LocalEmbeddingBootstrapService localEmbeddingBootstrapService;
    private final TacticRecordService tacticRecordService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;

    public SelfEvolvingController(SelfEvolvingProjectionService projectionService,
            PromotionWorkflowService promotionWorkflowService,
            BenchmarkLabService benchmarkLabService,
            LocalEmbeddingBootstrapService localEmbeddingBootstrapService,
            TacticRecordService tacticRecordService,
            HiveEventBatchPublisher hiveEventBatchPublisher) {
        this.projectionService = projectionService;
        this.promotionWorkflowService = promotionWorkflowService;
        this.benchmarkLabService = benchmarkLabService;
        this.localEmbeddingBootstrapService = localEmbeddingBootstrapService;
        this.tacticRecordService = tacticRecordService;
        this.hiveEventBatchPublisher = hiveEventBatchPublisher;
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
        return blocking(() -> ResponseEntity.ok(projectionService.listArtifacts(
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
        return blocking(() -> {
            SelfEvolvingArtifactWorkspaceSummaryDto summary = projectionService
                    .getArtifactWorkspaceSummary(artifactStreamId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(summary);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/lineage")
    public Mono<ResponseEntity<SelfEvolvingArtifactLineageDto>> getArtifactLineage(
            @PathVariable String artifactStreamId) {
        return blocking(() -> {
            SelfEvolvingArtifactLineageDto lineage = projectionService.getArtifactLineage(artifactStreamId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(lineage);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/diff")
    public Mono<ResponseEntity<SelfEvolvingArtifactRevisionDiffDto>> getArtifactRevisionDiff(
            @PathVariable String artifactStreamId,
            @RequestParam String fromRevisionId,
            @RequestParam String toRevisionId) {
        requireQueryParam("fromRevisionId", fromRevisionId);
        requireQueryParam("toRevisionId", toRevisionId);
        return blocking(() -> {
            SelfEvolvingArtifactRevisionDiffDto diff = projectionService
                    .getArtifactRevisionDiff(artifactStreamId, fromRevisionId, toRevisionId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(diff);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/transition-diff")
    public Mono<ResponseEntity<SelfEvolvingArtifactTransitionDiffDto>> getArtifactTransitionDiff(
            @PathVariable String artifactStreamId,
            @RequestParam String fromNodeId,
            @RequestParam String toNodeId) {
        requireQueryParam("fromNodeId", fromNodeId);
        requireQueryParam("toNodeId", toNodeId);
        return blocking(() -> {
            SelfEvolvingArtifactTransitionDiffDto diff = projectionService
                    .getArtifactTransitionDiff(artifactStreamId, fromNodeId, toNodeId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(diff);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getArtifactRevisionEvidence(
            @PathVariable String artifactStreamId,
            @RequestParam String revisionId) {
        requireQueryParam("revisionId", revisionId);
        return blocking(() -> {
            SelfEvolvingArtifactEvidenceDto evidence = projectionService
                    .getArtifactRevisionEvidence(artifactStreamId, revisionId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(evidence);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/compare-evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getArtifactCompareEvidence(
            @PathVariable String artifactStreamId,
            @RequestParam String fromRevisionId,
            @RequestParam String toRevisionId) {
        requireQueryParam("fromRevisionId", fromRevisionId);
        requireQueryParam("toRevisionId", toRevisionId);
        return blocking(() -> {
            SelfEvolvingArtifactEvidenceDto evidence = projectionService
                    .getArtifactCompareEvidence(artifactStreamId, fromRevisionId, toRevisionId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(evidence);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/transition-evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getArtifactTransitionEvidence(
            @PathVariable String artifactStreamId,
            @RequestParam String fromNodeId,
            @RequestParam String toNodeId) {
        requireQueryParam("fromNodeId", fromNodeId);
        requireQueryParam("toNodeId", toNodeId);
        return blocking(() -> {
            SelfEvolvingArtifactEvidenceDto evidence = projectionService
                    .getArtifactTransitionEvidence(artifactStreamId, fromNodeId, toNodeId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(evidence);
        });
    }

    @GetMapping("/artifacts/{artifactStreamId}/compare-options")
    public Mono<ResponseEntity<SelfEvolvingArtifactCompareOptionsDto>> getArtifactCompareOptions(
            @PathVariable String artifactStreamId) {
        return blocking(() -> {
            SelfEvolvingArtifactCompareOptionsDto compareOptions = projectionService
                    .getArtifactCompareOptions(artifactStreamId)
                    .orElseThrow(
                            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact stream not found"));
            return ResponseEntity.ok(compareOptions);
        });
    }

    @GetMapping("/tactics")
    public Mono<ResponseEntity<List<SelfEvolvingTacticDto>>> listTactics() {
        return blocking(() -> {
            List<SelfEvolvingTacticDto> tactics = projectionService.listTactics();
            publishHiveTacticCatalog(tactics);
            return ResponseEntity.ok(tactics);
        });
    }

    @GetMapping("/tactics/status")
    public Mono<ResponseEntity<SelfEvolvingTacticSearchStatusDto>> getTacticSearchStatus(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String baseUrl) {
        return blocking(() -> {
            if (localEmbeddingBootstrapService == null) {
                return ResponseEntity.ok(projectionService.getTacticSearchStatus());
            }
            return ResponseEntity.ok(
                    toTacticSearchStatusDto(localEmbeddingBootstrapService.probeStatus(provider, model, baseUrl)));
        });
    }

    @PostMapping("/tactics/install")
    public Mono<ResponseEntity<SelfEvolvingTacticSearchStatusDto>> installTacticEmbeddingModel(
            @RequestBody(required = false) TacticEmbeddingInstallRequest request) {
        return blocking(() -> {
            String requestedModel = request != null ? request.model() : null;
            TacticSearchStatus status = localEmbeddingBootstrapService.installModel(requestedModel);
            return ResponseEntity.ok(toTacticSearchStatusDto(status));
        });
    }

    @GetMapping("/tactics/search")
    public Mono<ResponseEntity<SelfEvolvingTacticSearchResponseDto>> searchTactics(
            @RequestParam(name = "q", required = false) String query) {
        return blocking(() -> {
            SelfEvolvingTacticSearchResponseDto response = projectionService.searchTactics(query);
            publishHiveTacticSearch(response);
            return ResponseEntity.ok(response);
        });
    }

    @GetMapping("/tactics/{tacticId}")
    public Mono<ResponseEntity<SelfEvolvingTacticDto>> getTactic(@PathVariable String tacticId) {
        return blocking(() -> {
            SelfEvolvingTacticDto tactic = projectionService.getTactic(tacticId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
            return ResponseEntity.ok(tactic);
        });
    }

    @GetMapping("/tactics/{tacticId}/explanation")
    public Mono<ResponseEntity<SelfEvolvingTacticSearchExplanationDto>> getTacticExplanation(
            @PathVariable String tacticId,
            @RequestParam(name = "q", required = false) String query) {
        return blocking(() -> {
            SelfEvolvingTacticSearchExplanationDto explanation = projectionService
                    .getTacticExplanation(tacticId, query)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
            return ResponseEntity.ok(explanation);
        });
    }

    @GetMapping("/tactics/{tacticId}/lineage")
    public Mono<ResponseEntity<SelfEvolvingArtifactLineageDto>> getTacticLineage(@PathVariable String tacticId) {
        return blocking(() -> {
            SelfEvolvingArtifactLineageDto lineage = projectionService.getTacticLineage(tacticId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
            return ResponseEntity.ok(lineage);
        });
    }

    @GetMapping("/tactics/{tacticId}/evidence")
    public Mono<ResponseEntity<SelfEvolvingArtifactEvidenceDto>> getTacticEvidence(@PathVariable String tacticId) {
        return blocking(() -> {
            SelfEvolvingArtifactEvidenceDto evidence = projectionService.getTacticEvidence(tacticId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tactic not found"));
            return ResponseEntity.ok(evidence);
        });
    }

    @PostMapping("/tactics/{tacticId}/deactivate")
    public Mono<ResponseEntity<Void>> deactivateTactic(@PathVariable String tacticId) {
        return blocking(() -> {
            tacticRecordService.deactivate(tacticId);
            return ResponseEntity.ok().build();
        });
    }

    @PostMapping("/tactics/{tacticId}/reactivate")
    public Mono<ResponseEntity<Void>> reactivateTactic(@PathVariable String tacticId) {
        return blocking(() -> {
            tacticRecordService.reactivate(tacticId);
            return ResponseEntity.ok().build();
        });
    }

    @DeleteMapping("/tactics/{tacticId}")
    public Mono<ResponseEntity<Void>> deleteTactic(@PathVariable String tacticId) {
        return blocking(() -> {
            tacticRecordService.delete(tacticId);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/candidates/{candidateId}/promotion")
    public Mono<ResponseEntity<SelfEvolvingPromotionDecisionDto>> planPromotion(@PathVariable String candidateId) {
        return blocking(
                () -> ResponseEntity.ok(toPromotionDecisionDto(promotionWorkflowService.planPromotion(candidateId))));
    }

    @GetMapping("/benchmarks/campaigns")
    public Mono<ResponseEntity<List<SelfEvolvingCampaignDto>>> listCampaigns() {
        return blocking(() -> ResponseEntity.ok(projectionService.listCampaigns()));
    }

    @PostMapping("/benchmarks/regression/{runId}")
    public Mono<ResponseEntity<SelfEvolvingCampaignDto>> createRegressionCampaign(@PathVariable String runId) {
        return blocking(() -> {
            BenchmarkCampaign campaign = benchmarkLabService.createRegressionCampaign(runId);
            publishHiveCampaignProjection(campaign);
            return ResponseEntity.ok(toCampaignDto(campaign));
        });
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
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

    private SelfEvolvingTacticSearchStatusDto toTacticSearchStatusDto(TacticSearchStatus status) {
        return SelfEvolvingTacticSearchStatusDto.builder()
                .mode(status.getMode())
                .reason(status.getReason())
                .provider(status.getProvider())
                .model(status.getModel())
                .degraded(status.getDegraded())
                .runtimeState(status.getRuntimeState())
                .owned(status.getOwned())
                .runtimeInstalled(status.getRuntimeInstalled())
                .runtimeHealthy(status.getRuntimeHealthy())
                .runtimeVersion(status.getRuntimeVersion())
                .baseUrl(status.getBaseUrl())
                .modelAvailable(status.getModelAvailable())
                .restartAttempts(status.getRestartAttempts())
                .nextRetryAt(status.getNextRetryAt() != null ? status.getNextRetryAt().toString() : null)
                .nextRetryTime(status.getNextRetryTime())
                .autoInstallConfigured(status.getAutoInstallConfigured())
                .pullOnStartConfigured(status.getPullOnStartConfigured())
                .pullAttempted(status.getPullAttempted())
                .pullSucceeded(status.getPullSucceeded())
                .updatedAt(status.getUpdatedAt() != null ? status.getUpdatedAt().toString() : null)
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
