package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.domain.model.selfevolving.BenchmarkCampaign;
import me.golemcore.bot.domain.model.selfevolving.PromotionDecision;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingPromotionControllerTest {

    private SelfEvolvingProjectionService projectionService;
    private PromotionWorkflowService promotionWorkflowService;
    private BenchmarkLabService benchmarkLabService;
    private SelfEvolvingRunsController runsController;
    private SelfEvolvingBenchmarksController benchmarksController;

    @BeforeEach
    void setUp() {
        projectionService = mock(SelfEvolvingProjectionService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        runsController = new SelfEvolvingRunsController(projectionService, promotionWorkflowService);
        benchmarksController = new SelfEvolvingBenchmarksController(projectionService, benchmarkLabService, null);
    }

    @Test
    void shouldPlanPromotionForCandidate() {
        when(promotionWorkflowService.planPromotion("candidate-1")).thenReturn(PromotionDecision.builder()
                .candidateId("candidate-1")
                .state("active")
                .mode("approval_gate")
                .decidedAt(Instant.parse("2026-03-31T16:35:00Z"))
                .build());

        StepVerifier.create(runsController.planPromotion("candidate-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("active", response.getBody().getState());
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateRegressionCampaignFromRun() {
        when(benchmarkLabService.createRegressionCampaign("run-1")).thenReturn(BenchmarkCampaign.builder()
                .id("campaign-1")
                .suiteId("suite-1")
                .status("created")
                .runIds(List.of("run-1"))
                .build());

        StepVerifier.create(benchmarksController.createRegressionCampaign("run-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("suite-1", response.getBody().getSuiteId());
                })
                .verifyComplete();
    }
}
