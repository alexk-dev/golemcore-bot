package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceBudgetService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.TraceSnapshotCompressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticTurnContextServiceTracingTest {

    private RuntimeConfigService runtimeConfigService;
    private TacticSearchService tacticSearchService;
    private TraceService traceService;
    private TacticTurnContextService tacticTurnContextService;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        tacticSearchService = mock(TacticSearchService.class);
        TraceSnapshotCompressionService compressionService = mock(TraceSnapshotCompressionService.class);
        TraceBudgetService traceBudgetService = mock(TraceBudgetService.class);
        traceService = new TraceService(compressionService, traceBudgetService);
        tacticTurnContextService = new TacticTurnContextService(
                runtimeConfigService,
                tacticSearchService,
                traceService);
    }

    @Test
    void shouldCreateTacticEnrichSpanWhenTracingIsEnabled() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        AgentContext context = contextWithTrace();
        TacticSearchQuery query = TacticSearchQuery.builder()
                .rawQuery("recover failed shell")
                .queryViews(List.of("recover", "shell"))
                .build();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .title("Planner")
                .promotionState("active")
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .finalScore(0.85d)
                        .rerankerVerdict("tier deep via gpt-5.4/high")
                        .build())
                .build()));

        tacticTurnContextService.attach(context);

        TraceRecord trace = context.getSession().getTraces().getFirst();
        TraceSpanRecord tacticSpan = trace.getSpans().stream()
                .filter(span -> "tactic.enrich".equals(span.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(tacticSpan);
        assertEquals(TraceSpanKind.INTERNAL, tacticSpan.getKind());
        assertEquals(TraceStatusCode.OK, tacticSpan.getStatusCode());
        assertNotNull(tacticSpan.getEndedAt());
        assertEquals("tactic.enrich", tacticSpan.getAttributes().get("component"));
    }

    @Test
    void shouldRecordTacticSearchCompletedEventWithSelectionAttributes() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        AgentContext context = contextWithTrace();
        TacticSearchQuery query = TacticSearchQuery.builder()
                .rawQuery("recover")
                .queryViews(List.of("recover"))
                .build();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .title("Planner")
                .promotionState("active")
                .explanation(TacticSearchExplanation.builder()
                        .searchMode("hybrid")
                        .finalScore(0.85d)
                        .rerankerVerdict("tier deep via gpt-5.4/high")
                        .build())
                .build()));

        tacticTurnContextService.attach(context);

        TraceSpanRecord tacticSpan = context.getSession().getTraces().getFirst().getSpans().stream()
                .filter(span -> "tactic.enrich".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(tacticSpan.getEvents());
        assertEquals(1, tacticSpan.getEvents().size());
        assertEquals("tactic.search.completed", tacticSpan.getEvents().getFirst().getName());
        assertEquals("planner", tacticSpan.getEvents().getFirst().getAttributes().get("tactic.selected_id"));
        assertEquals("hybrid", tacticSpan.getEvents().getFirst().getAttributes().get("tactic.search_mode"));
        assertEquals(0.85d, tacticSpan.getEvents().getFirst().getAttributes().get("tactic.final_score"));
        assertEquals(1, tacticSpan.getEvents().getFirst().getAttributes().get("tactic.result_count"));
        assertEquals("tier deep via gpt-5.4/high",
                tacticSpan.getEvents().getFirst().getAttributes().get("tactic.reranker_verdict"));
    }

    @Test
    void shouldRecordZeroResultCountWhenSearchReturnsEmpty() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        AgentContext context = contextWithTrace();
        TacticSearchQuery query = TacticSearchQuery.builder()
                .rawQuery("nothing")
                .queryViews(List.of("nothing"))
                .build();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of());

        tacticTurnContextService.attach(context);

        TraceSpanRecord tacticSpan = context.getSession().getTraces().getFirst().getSpans().stream()
                .filter(span -> "tactic.enrich".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(TraceStatusCode.OK, tacticSpan.getStatusCode());
        assertEquals(0, tacticSpan.getEvents().getFirst().getAttributes().get("tactic.result_count"));
        assertNull(tacticSpan.getEvents().getFirst().getAttributes().get("tactic.selected_id"));
    }

    @Test
    void shouldFinishSpanWithErrorStatusWhenSearchThrows() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(true);
        AgentContext context = contextWithTrace();
        when(tacticSearchService.buildQuery(context))
                .thenThrow(new RuntimeException("search index unavailable"));

        tacticTurnContextService.attach(context);

        TraceSpanRecord tacticSpan = context.getSession().getTraces().getFirst().getSpans().stream()
                .filter(span -> "tactic.enrich".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(TraceStatusCode.ERROR, tacticSpan.getStatusCode());
        assertTrue(tacticSpan.getStatusMessage().contains("search index unavailable"));
        assertNotNull(tacticSpan.getEndedAt());
    }

    @Test
    void shouldSkipTracingWhenTracingIsDisabledButStillAttachTactics() {
        when(runtimeConfigService.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigService.isTracingEnabled()).thenReturn(false);
        AgentContext context = contextWithTrace();
        TacticSearchQuery query = TacticSearchQuery.builder()
                .rawQuery("recover")
                .queryViews(List.of("recover"))
                .build();
        when(tacticSearchService.buildQuery(context)).thenReturn(query);
        when(tacticSearchService.search(query)).thenReturn(List.of(TacticSearchResult.builder()
                .tacticId("planner")
                .title("Planner")
                .promotionState("active")
                .build()));

        tacticTurnContextService.attach(context);

        assertNotNull(context.getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION));
        long tacticSpanCount = context.getSession().getTraces().getFirst().getSpans().stream()
                .filter(span -> "tactic.enrich".equals(span.getName()))
                .count();
        assertEquals(0, tacticSpanCount);
    }

    private AgentContext contextWithTrace() {
        AgentSession session = AgentSession.builder()
                .id("session-trace")
                .chatId("chat-trace")
                .messages(new ArrayList<>())
                .traces(new ArrayList<>())
                .traceStorageStats(new TraceStorageStats())
                .build();
        TraceContext rootTrace = traceService.startRootTrace(
                session, "turn", TraceSpanKind.INGRESS, java.time.Instant.now(), java.util.Map.of());
        return AgentContext.builder()
                .session(session)
                .traceContext(rootTrace)
                .messages(new ArrayList<>(List.of(Message.builder()
                        .role("user")
                        .content("recover from failed shell command")
                        .build())))
                .build();
    }
}
