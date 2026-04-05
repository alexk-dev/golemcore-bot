package me.golemcore.bot.adapter.inbound.web.controller;

import static me.golemcore.bot.adapter.inbound.web.controller.SelfEvolvingControllerSupport.blocking;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.selfevolving.tactic.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * SelfEvolving tactic catalog, search and lifecycle endpoints.
 */
@RestController
@RequestMapping("/api/self-evolving")
@Slf4j
public class SelfEvolvingTacticsController {

    public record TacticEmbeddingInstallRequest(String model) {
    }

    private final SelfEvolvingProjectionService projectionService;
    private final LocalEmbeddingBootstrapService localEmbeddingBootstrapService;
    private final TacticRecordService tacticRecordService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;

    public SelfEvolvingTacticsController(SelfEvolvingProjectionService projectionService,
            LocalEmbeddingBootstrapService localEmbeddingBootstrapService,
            TacticRecordService tacticRecordService,
            HiveEventBatchPublisher hiveEventBatchPublisher) {
        this.projectionService = projectionService;
        this.localEmbeddingBootstrapService = localEmbeddingBootstrapService;
        this.tacticRecordService = tacticRecordService;
        this.hiveEventBatchPublisher = hiveEventBatchPublisher;
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
}
