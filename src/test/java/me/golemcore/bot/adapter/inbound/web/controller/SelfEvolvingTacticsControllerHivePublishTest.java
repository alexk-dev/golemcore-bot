package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchExplanationDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResponseDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchResultDto;
import me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic.SelfEvolvingTacticSearchStatusDto;
import me.golemcore.bot.adapter.inbound.web.projection.SelfEvolvingProjectionService;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.tactic.TacticRecordService;
import me.golemcore.bot.port.outbound.HiveEventPublishPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
