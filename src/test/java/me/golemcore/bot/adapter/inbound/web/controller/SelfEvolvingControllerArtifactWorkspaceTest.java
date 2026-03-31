package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCatalogEntryDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactCompareOptionsDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactEvidenceDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactLineageDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.artifact.SelfEvolvingArtifactWorkspaceSummaryDto;
import me.golemcore.bot.domain.service.BenchmarkLabService;
import me.golemcore.bot.domain.service.PromotionWorkflowService;
import me.golemcore.bot.domain.service.SelfEvolvingProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingControllerArtifactWorkspaceTest {

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
    void shouldListArtifactWorkspaceCatalog() {
        when(projectionService.listArtifacts(null, null, null, null, null, null, null, null)).thenReturn(List.of(
                SelfEvolvingArtifactCatalogEntryDto.builder()
                        .artifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .artifactType("skill")
                        .artifactSubtype("skill")
                        .activeRevisionId("rev-1")
                        .latestCandidateRevisionId("rev-2")
                        .build()));

        StepVerifier.create(controller.listArtifacts(null, null, null, null, null, null, null, null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("stream-1", response.getBody().getFirst().getArtifactStreamId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnArtifactWorkspaceSummaryByStreamId() {
        when(projectionService.getArtifactWorkspaceSummary("stream-1")).thenReturn(Optional.of(
                SelfEvolvingArtifactWorkspaceSummaryDto.builder()
                        .artifactStreamId("stream-1")
                        .originArtifactStreamId("stream-1")
                        .artifactKey("skill:planner")
                        .artifactAliases(List.of("skill:planner"))
                        .activeRevisionId("rev-1")
                        .latestCandidateRevisionId("rev-2")
                        .compareOptions(SelfEvolvingArtifactCompareOptionsDto.builder()
                                .artifactStreamId("stream-1")
                                .defaultFromRevisionId("rev-1")
                                .defaultToRevisionId("rev-2")
                                .build())
                        .build()));

        StepVerifier.create(controller.getArtifactWorkspaceSummary("stream-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("stream-1", response.getBody().getArtifactStreamId());
                    assertEquals("rev-1", response.getBody().getCompareOptions().getDefaultFromRevisionId());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnArtifactLineageAndEvidenceByStreamId() {
        when(projectionService.getArtifactLineage("stream-1")).thenReturn(Optional.of(
                SelfEvolvingArtifactLineageDto.builder()
                        .artifactStreamId("stream-1")
                        .defaultSelectedNodeId("candidate-1:proposed")
                        .defaultSelectedRevisionId("rev-2")
                        .build()));
        when(projectionService.getArtifactRevisionEvidence("stream-1", "rev-2")).thenReturn(Optional.of(
                SelfEvolvingArtifactEvidenceDto.builder()
                        .artifactStreamId("stream-1")
                        .payloadKind("revision")
                        .revisionId("rev-2")
                        .runIds(List.of("run-2"))
                        .findings(List.of("revision_evidence"))
                        .build()));

        StepVerifier.create(controller.getArtifactLineage("stream-1"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("candidate-1:proposed", response.getBody().getDefaultSelectedNodeId());
                })
                .verifyComplete();

        StepVerifier.create(controller.getArtifactRevisionEvidence("stream-1", "rev-2"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("revision", response.getBody().getPayloadKind());
                    assertEquals(List.of("run-2"), response.getBody().getRunIds());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectMissingRevisionDiffQueryParams() {
        ResponseStatusException responseStatusException = assertThrows(
                ResponseStatusException.class,
                () -> controller.getArtifactRevisionDiff("stream-1", null, "rev-2"));

        assertEquals(HttpStatus.BAD_REQUEST, responseStatusException.getStatusCode());
    }
}
