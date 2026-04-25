package me.golemcore.bot.adapter.inbound.web.controller;

import static me.golemcore.bot.adapter.inbound.web.controller.SelfEvolvingControllerSupport.blocking;
import static me.golemcore.bot.adapter.inbound.web.controller.SelfEvolvingControllerSupport.requireQueryParam;

import java.util.List;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCompareOptionsDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactRevisionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactTransitionDiffDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactWorkspaceSummaryDto;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * SelfEvolving artifact workspace endpoints (catalog, lineage, diffs,
 * evidence).
 */
@RestController
@RequestMapping("/api/self-evolving")
public class SelfEvolvingArtifactsController {

    private final SelfEvolvingProjectionService projectionService;

    public SelfEvolvingArtifactsController(SelfEvolvingProjectionService projectionService) {
        this.projectionService = projectionService;
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
}
