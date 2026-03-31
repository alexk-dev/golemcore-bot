package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingCandidateDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunDetailDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.SelfEvolvingRunSummaryDto;
import me.golemcore.bot.domain.service.BenchmarkLabService;
import me.golemcore.bot.domain.service.PromotionWorkflowService;
import me.golemcore.bot.domain.service.SelfEvolvingProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingControllerTest {

    private SelfEvolvingProjectionService projectionService;
    private PromotionWorkflowService promotionWorkflowService;
    private BenchmarkLabService benchmarkLabService;
    private SelfEvolvingController controller;

    @BeforeEach
    void setUp() {
        projectionService = mock(SelfEvolvingProjectionService.class);
        promotionWorkflowService = mock(PromotionWorkflowService.class);
        benchmarkLabService = mock(BenchmarkLabService.class);
        controller = new SelfEvolvingController(projectionService, promotionWorkflowService, benchmarkLabService);
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
}
