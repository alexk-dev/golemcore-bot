package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResultDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.domain.selfevolving.benchmark.BenchmarkLabService;
import me.golemcore.bot.domain.selfevolving.promotion.PromotionWorkflowService;
import me.golemcore.bot.domain.selfevolving.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingControllerTacticSearchTest {

    private SelfEvolvingProjectionService projectionService;
    private TacticRecordService tacticRecordService;
    private SelfEvolvingController controller;

    @BeforeEach
    void setUp() {
        projectionService = mock(SelfEvolvingProjectionService.class);
        tacticRecordService = mock(TacticRecordService.class);
        controller = new SelfEvolvingController(
                projectionService,
                mock(PromotionWorkflowService.class),
                mock(BenchmarkLabService.class),
                null,
                tacticRecordService,
                null);
    }

    @Test
    void shouldReturnSearchStatusAndExplanationBreakdown() {
        when(projectionService.searchTactics("planner")).thenReturn(SelfEvolvingTacticSearchResponseDto.builder()
                .query("planner")
                .status(SelfEvolvingTacticSearchStatusDto.builder()
                        .mode("hybrid")
                        .reason(null)
                        .build())
                .results(List.of(SelfEvolvingTacticSearchResultDto.builder()
                        .tacticId("planner")
                        .artifactKey("skill:planner")
                        .title("Planner tactic")
                        .promotionState("active")
                        .explanation(SelfEvolvingTacticSearchExplanationDto.builder()
                                .bm25Score(0.5d)
                                .personalizationBoost(0.08d)
                                .build())
                        .build()))
                .build());

        StepVerifier.create(controller.searchTactics("planner"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("hybrid", response.getBody().getStatus().getMode());
                    assertEquals(0.5d, response.getBody().getResults().getFirst().getExplanation().getBm25Score());
                    assertEquals(0.08d,
                            response.getBody().getResults().getFirst().getExplanation().getPersonalizationBoost());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnDedicatedTacticSearchStatusForSettings() {
        when(projectionService.getTacticSearchStatus()).thenReturn(SelfEvolvingTacticSearchStatusDto.builder()
                .mode("hybrid")
                .reason("local embedding model unavailable")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .degraded(true)
                .runtimeHealthy(false)
                .modelAvailable(false)
                .autoInstallConfigured(true)
                .pullOnStartConfigured(true)
                .pullAttempted(true)
                .pullSucceeded(false)
                .updatedAt("2026-04-01T23:30:00Z")
                .build());

        StepVerifier.create(controller.getTacticSearchStatus(null, null, null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("ollama", response.getBody().getProvider());
                    assertEquals("qwen3-embedding:0.6b", response.getBody().getModel());
                    assertEquals(false, response.getBody().getRuntimeHealthy());
                    assertEquals(true, response.getBody().getPullAttempted());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnTacticDetailExplanationLineageAndEvidence() {
        when(projectionService.getTactic("planner")).thenReturn(Optional.of(SelfEvolvingTacticDto.builder()
                .tacticId("planner")
                .artifactStreamId("stream-1")
                .artifactKey("skill:planner")
                .title("Planner tactic")
                .build()));
        when(projectionService.getTacticExplanation("planner", "planner")).thenReturn(Optional.of(
                SelfEvolvingTacticSearchExplanationDto.builder()
                        .matchedTerms(List.of("planner"))
                        .eligible(true)
                        .build()));
        when(projectionService.getTacticLineage("planner")).thenReturn(Optional.of(
                SelfEvolvingArtifactLineageDto.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .defaultSelectedNodeId("candidate-1:active")
                        .build()));
        when(projectionService.getTacticEvidence("planner")).thenReturn(Optional.of(
                SelfEvolvingArtifactEvidenceDto.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .payloadKind("revision")
                        .findings(List.of("revision_evidence"))
                        .build()));

        StepVerifier.create(controller.getTactic("planner"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("planner", response.getBody().getTacticId());
                })
                .verifyComplete();

        StepVerifier.create(controller.getTacticExplanation("planner", "planner"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(List.of("planner"), response.getBody().getMatchedTerms());
                })
                .verifyComplete();

        StepVerifier.create(controller.getTacticLineage("planner"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("candidate-1:active", response.getBody().getDefaultSelectedNodeId());
                })
                .verifyComplete();

        StepVerifier.create(controller.getTacticEvidence("planner"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(List.of("revision_evidence"), response.getBody().getFindings());
                })
                .verifyComplete();
    }
}
