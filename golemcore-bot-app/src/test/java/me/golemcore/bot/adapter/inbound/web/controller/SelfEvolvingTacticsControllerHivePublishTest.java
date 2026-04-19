package me.golemcore.bot.adapter.inbound.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResultDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfEvolvingTacticsControllerHivePublishTest {

    private SelfEvolvingProjectionService projectionService;
    private TacticRecordService tacticRecordService;
    private HiveEventPublishPort hiveEventPublishPort;
    private SelfEvolvingTacticsController controller;

    @BeforeEach
    void setUp() {
        projectionService = mock(SelfEvolvingProjectionService.class);
        tacticRecordService = mock(TacticRecordService.class);
        hiveEventPublishPort = mock(HiveEventPublishPort.class);
        controller = new SelfEvolvingTacticsController(
                projectionService,
                null,
                tacticRecordService,
                hiveEventPublishPort);
    }

    @Test
    void shouldPublishDomainTacticSearchProjectionForHive() {
        when(projectionService.searchTactics("planner")).thenReturn(SelfEvolvingTacticSearchResponseDto.builder()
                .query("planner")
                .status(SelfEvolvingTacticSearchStatusDto.builder()
                        .mode("hybrid")
                        .provider("ollama")
                        .model("qwen3-embedding:0.6b")
                        .degraded(true)
                        .runtimeState("degraded_restart_backoff")
                        .updatedAt("2026-04-06T04:00:00Z")
                        .build())
                .results(List.of(SelfEvolvingTacticSearchResultDto.builder()
                        .tacticId("planner")
                        .artifactStreamId("stream-planner")
                        .artifactKey("skill:planner")
                        .artifactType("skill")
                        .title("Planner")
                        .score(1.1d)
                        .updatedAt("2026-04-06T04:00:00Z")
                        .explanation(SelfEvolvingTacticSearchExplanationDto.builder()
                                .searchMode("hybrid")
                                .eligible(true)
                                .finalScore(1.1d)
                                .build())
                        .build()))
                .build());

        controller.searchTactics("planner").block();

        verify(hiveEventPublishPort).publishSelfEvolvingTacticSearchProjection(
                argThat(query -> "planner".equals(query)),
                argThat(status -> status != null
                        && "hybrid".equals(status.getMode())
                        && "degraded_restart_backoff".equals(status.getRuntimeState())
                        && Boolean.TRUE.equals(status.getDegraded())),
                argThat(results -> results != null
                        && results.size() == 1
                        && "planner".equals(results.getFirst().getTacticId())
                        && results.getFirst().getExplanation() != null
                        && "hybrid".equals(results.getFirst().getExplanation().getSearchMode())));
    }

    @Test
    void shouldPublishEmptyDomainResultsWhenProjectionResultsMissing() {
        when(projectionService.searchTactics("planner")).thenReturn(SelfEvolvingTacticSearchResponseDto.builder()
                .query("planner")
                .status(SelfEvolvingTacticSearchStatusDto.builder().mode("bm25").build())
                .results(null)
                .build());

        controller.searchTactics("planner").block();

        verify(hiveEventPublishPort).publishSelfEvolvingTacticSearchProjection(
                argThat(query -> "planner".equals(query)),
                any(TacticSearchStatus.class),
                anyList());
    }

    @Test
    void shouldMapNullStatusAndExplanationAndInvalidInstantsForHivePublish() {
        when(projectionService.searchTactics("planner")).thenReturn(SelfEvolvingTacticSearchResponseDto.builder()
                .query("planner")
                .status(null)
                .results(List.of(SelfEvolvingTacticSearchResultDto.builder()
                        .tacticId("planner")
                        .artifactKey("skill:planner")
                        .updatedAt("not-an-instant")
                        .explanation(null)
                        .build()))
                .build());

        controller.searchTactics("planner").block();

        verify(hiveEventPublishPort).publishSelfEvolvingTacticSearchProjection(
                argThat(query -> "planner".equals(query)),
                argThat(status -> status == null),
                argThat(results -> results != null
                        && results.size() == 1
                        && results.getFirst().getUpdatedAt() == null
                        && results.getFirst().getExplanation() == null));
    }

    @Test
    void shouldSkipTacticCatalogPublishWhenPortMissingOrListEmpty() {
        SelfEvolvingTacticsController controllerWithoutPort = new SelfEvolvingTacticsController(
                projectionService,
                null,
                tacticRecordService,
                null);
        when(projectionService.listTactics()).thenReturn(List.of(SelfEvolvingTacticDto.builder()
                .tacticId("planner")
                .artifactKey("skill:planner")
                .title("Planner")
                .build()));

        controllerWithoutPort.listTactics().block();

        when(projectionService.listTactics()).thenReturn(List.of());
        controller.listTactics().block();
    }

    @Test
    void shouldPublishTacticCatalogWhenPortAvailable() {
        List<SelfEvolvingTacticDto> tactics = List.of(SelfEvolvingTacticDto.builder()
                .tacticId("planner")
                .artifactStreamId("stream-planner")
                .artifactKey("skill:planner")
                .artifactType("skill")
                .title("Planner")
                .aliases(List.of("plan"))
                .updatedAt("2026-04-06T04:00:00Z")
                .build());
        when(projectionService.listTactics()).thenReturn(tactics);

        controller.listTactics().block();

        verify(hiveEventPublishPort).publishSelfEvolvingTacticCatalogProjection(
                argThat(projections -> projections != null
                        && projections.size() == 1
                        && matchesProjection(projections.getFirst())));
    }

    @Test
    void shouldSwallowHiveTacticSearchPublishFailures() {
        when(projectionService.searchTactics("planner")).thenReturn(SelfEvolvingTacticSearchResponseDto.builder()
                .query("planner")
                .status(SelfEvolvingTacticSearchStatusDto.builder().mode("bm25").build())
                .results(List.of())
                .build());
        doThrow(new IllegalStateException("publish failed"))
                .when(hiveEventPublishPort)
                .publishSelfEvolvingTacticSearchProjection(any(String.class), any(), anyList());

        controller.searchTactics("planner").block();
    }

    private boolean matchesProjection(TacticSearchResult projection) {
        return "planner".equals(projection.getTacticId())
                && "stream-planner".equals(projection.getArtifactStreamId())
                && "skill:planner".equals(projection.getArtifactKey())
                && "skill".equals(projection.getArtifactType())
                && "Planner".equals(projection.getTitle())
                && List.of("plan").equals(projection.getAliases())
                && projection.getUpdatedAt() != null;
    }
}
