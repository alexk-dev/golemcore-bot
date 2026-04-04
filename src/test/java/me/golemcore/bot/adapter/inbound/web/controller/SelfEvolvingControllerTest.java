package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.service.BenchmarkLabService;
import me.golemcore.bot.domain.service.LocalEmbeddingBootstrapService;
import me.golemcore.bot.domain.service.PromotionWorkflowService;
import me.golemcore.bot.domain.service.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.service.TacticRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingControllerTest {

    private SelfEvolvingProjectionService projectionService;
    private PromotionWorkflowService promotionWorkflowService;
    private BenchmarkLabService benchmarkLabService;
    private LocalEmbeddingBootstrapService localEmbeddingBootstrapService;
    private TacticRecordService tacticRecordService;
    private SelfEvolvingController controller;

    @BeforeEach
    void setUp() {
        projectionService = mock(SelfEvolvingProjectionService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        localEmbeddingBootstrapService = mock(LocalEmbeddingBootstrapService.class);
        tacticRecordService = mock(TacticRecordService.class);
        controller = new SelfEvolvingController(projectionService, promotionWorkflowService, benchmarkLabService,
                localEmbeddingBootstrapService, tacticRecordService, null);
    }

    @Test
    void shouldListRunsForTheDashboard() {
        when(projectionService.listRuns()).thenReturn(List.of(SelfEvolvingRunSummaryDto.builder()
                .id("run-1")
                .status("COMPLETED")
                .build()));

        StepVerifier.create(controller.listRuns())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<SelfEvolvingRunSummaryDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals("run-1", body.get(0).getId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnRunDetailWhenPresent() {
        when(projectionService.getRun("run-1")).thenReturn(Optional.of(SelfEvolvingRunDetailDto.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .verdict(SelfEvolvingRunDetailDto.VerdictDto.builder()
                        .outcomeStatus("COMPLETED")
                        .promotionRecommendation("approve_gated")
                        .build())
                .build()));

        StepVerifier.create(controller.getRun("run-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("run-1", response.getBody().getId());
                    assertEquals("bundle-1", response.getBody().getArtifactBundleId());
                })
                .verifyComplete();
    }

    @Test
    void shouldListEmptyCandidateQueue() {
        when(projectionService.listCandidates()).thenReturn(List.of(SelfEvolvingCandidateDto.builder().build()));

        StepVerifier.create(controller.listCandidates())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertTrue(response.getBody().size() == 1);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectMissingRunDetail() {
        when(projectionService.getRun("missing")).thenReturn(Optional.empty());

        StepVerifier.create(controller.getRun("missing"))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ResponseStatusException);
                    assertEquals(HttpStatus.NOT_FOUND, ((ResponseStatusException) error).getStatusCode());
                })
                .verify();
    }

    @Test
    void shouldPlanPromotionAndListCampaigns() {
        when(promotionWorkflowService.planPromotion("candidate-1")).thenReturn(PromotionDecision.builder()
                .id("decision-1")
                .candidateId("candidate-1")
                .state("active")
                .build());
        when(projectionService.listCampaigns()).thenReturn(List.of());

        StepVerifier.create(controller.planPromotion("candidate-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("decision-1", response.getBody().getId());
                    assertEquals("active", response.getBody().getState());
                })
                .verifyComplete();

        StepVerifier.create(controller.listCampaigns())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertTrue(response.getBody().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateRegressionCampaign() {
        when(benchmarkLabService.createRegressionCampaign("run-9")).thenReturn(BenchmarkCampaign.builder()
                .id("campaign-9")
                .status("created")
                .runIds(List.of("run-9"))
                .build());

        StepVerifier.create(controller.createRegressionCampaign("run-9"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("campaign-9", response.getBody().getId());
                    assertEquals(List.of("run-9"), response.getBody().getRunIds());
                })
                .verifyComplete();
    }

    @Test
    void shouldInstallRequestedTacticEmbeddingModel() {
        when(localEmbeddingBootstrapService.installModel("bge-m3")).thenReturn(TacticSearchStatus.builder()
                .mode("hybrid")
                .provider("ollama")
                .model("bge-m3")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .runtimeVersion("0.19.0")
                .baseUrl("http://127.0.0.1:11434")
                .modelAvailable(true)
                .pullAttempted(true)
                .pullSucceeded(true)
                .build());

        StepVerifier.create(controller.installTacticEmbeddingModel(
                new SelfEvolvingController.TacticEmbeddingInstallRequest("bge-m3")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("ollama", response.getBody().getProvider());
                    assertEquals("bge-m3", response.getBody().getModel());
                    assertTrue(response.getBody().getModelAvailable());
                    assertTrue(response.getBody().getPullSucceeded());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposeDedicatedOllamaDiagnosticsForTacticSearchStatus() {
        when(localEmbeddingBootstrapService.probeStatus(null, null, null)).thenReturn(TacticSearchStatus.builder()
                .mode("bm25")
                .reason("Ollama is not installed on this machine")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeState("degraded_missing_binary")
                .owned(false)
                .restartAttempts(0)
                .nextRetryTime(null)
                .runtimeInstalled(false)
                .runtimeHealthy(false)
                .runtimeVersion(null)
                .baseUrl("http://127.0.0.1:11434")
                .modelAvailable(false)
                .degraded(true)
                .build());

        StepVerifier.create(controller.getTacticSearchStatus(null, null, null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("ollama", response.getBody().getProvider());
                    assertEquals("degraded_missing_binary", response.getBody().getRuntimeState());
                    assertFalse(Boolean.TRUE.equals(response.getBody().getOwned()));
                    assertEquals(0, response.getBody().getRestartAttempts());
                    assertFalse(Boolean.TRUE.equals(response.getBody().getRuntimeInstalled()));
                    assertEquals("http://127.0.0.1:11434", response.getBody().getBaseUrl());
                })
                .verifyComplete();
    }

    @Test
    void shouldExposePreviewDiagnosticsForUnsavedLocalEmbeddingSelection() {
        when(localEmbeddingBootstrapService.probeStatus("ollama", "bge-m3", null))
                .thenReturn(TacticSearchStatus.builder()
                        .mode("hybrid")
                        .reason(null)
                        .provider("ollama")
                        .model("bge-m3")
                        .runtimeInstalled(true)
                        .runtimeHealthy(true)
                        .runtimeVersion("0.19.0")
                        .baseUrl("http://127.0.0.1:11434")
                        .modelAvailable(true)
                        .degraded(false)
                        .build());

        StepVerifier.create(controller.getTacticSearchStatus("ollama", "bge-m3", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("ollama", response.getBody().getProvider());
                    assertEquals("bge-m3", response.getBody().getModel());
                    assertTrue(Boolean.TRUE.equals(response.getBody().getModelAvailable()));
                })
                .verifyComplete();
    }

    @Test
    void shouldDeactivateTactic() {
        StepVerifier.create(controller.deactivateTactic("tactic-1"))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldDeleteTactic() {
        StepVerifier.create(controller.deleteTactic("tactic-1"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
    }
}
