package me.golemcore.bot.domain.selfevolving.tactic;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchExplanation;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceEventRecord;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.TraceOperationsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticTurnContextServiceTracingTest {

    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private TacticSearchService tacticSearchService;
    private TraceOperationsPort traceOperationsPort;
    private TacticTurnContextService tacticTurnContextService;

    @BeforeEach
    void setUp() {
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        tacticSearchService = mock(TacticSearchService.class);
        traceOperationsPort = new InMemoryTraceOperationsPort();
        tacticTurnContextService = new TacticTurnContextService(
                runtimeConfigPort,
                tacticSearchService,
                traceOperationsPort);
    }

    @Test
    void shouldCreateTacticEnrichSpanWhenTracingIsEnabled() {
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
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
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
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
    }

    @Test
    void shouldRecordZeroResultCountWhenSearchReturnsEmpty() {
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
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
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.isTracingEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
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
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(runtimeConfigPort.isTracingEnabled()).thenReturn(false);
        when(runtimeConfigPort.getTacticAdvisoryCount()).thenReturn(1);
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
        TraceContext rootTrace = traceOperationsPort.startRootTrace(
                session, "turn", TraceSpanKind.INGRESS, Instant.now(), Map.of());
        return AgentContext.builder()
                .session(session)
                .traceContext(rootTrace)
                .messages(new ArrayList<>(List.of(Message.builder()
                        .role("user")
                        .content("recover from failed shell command")
                        .build())))
                .build();
    }

    private static final class InMemoryTraceOperationsPort implements TraceOperationsPort {

        @Override
        public TraceContext startRootTrace(AgentSession session, String traceName, TraceSpanKind kind,
                Instant startedAt, Map<String, Object> attributes) {
            return startRootTrace(session, null, traceName, kind, startedAt, attributes, null);
        }

        @Override
        public TraceContext startRootTrace(AgentSession session, String traceName, TraceSpanKind kind,
                Instant startedAt, Map<String, Object> attributes, Integer maxTracesPerSession) {
            return startRootTrace(session, null, traceName, kind, startedAt, attributes, maxTracesPerSession);
        }

        @Override
        public TraceContext startRootTrace(AgentSession session, TraceContext rootContext, String traceName,
                TraceSpanKind kind, Instant startedAt, Map<String, Object> attributes) {
            return startRootTrace(session, rootContext, traceName, kind, startedAt, attributes, null);
        }

        @Override
        public TraceContext startRootTrace(AgentSession session, TraceContext rootContext, String traceName,
                TraceSpanKind kind, Instant startedAt, Map<String, Object> attributes, Integer maxTracesPerSession) {
            ensureSessionTraceState(session);
            String traceId = rootContext != null && rootContext.getTraceId() != null
                    ? rootContext.getTraceId()
                    : UUID.randomUUID().toString();
            String spanId = rootContext != null && rootContext.getSpanId() != null
                    ? rootContext.getSpanId()
                    : UUID.randomUUID().toString();
            TraceSpanRecord rootSpan = TraceSpanRecord.builder()
                    .spanId(spanId)
                    .name(traceName)
                    .kind(kind)
                    .startedAt(startedAt)
                    .attributes(copyAttributes(attributes))
                    .build();
            TraceRecord traceRecord = TraceRecord.builder()
                    .traceId(traceId)
                    .rootSpanId(spanId)
                    .traceName(traceName)
                    .startedAt(startedAt)
                    .compressedSnapshotBytes(0L)
                    .uncompressedSnapshotBytes(0L)
                    .build();
            traceRecord.getSpans().add(rootSpan);
            session.getTraces().add(traceRecord);
            return TraceContext.builder()
                    .traceId(traceId)
                    .spanId(spanId)
                    .parentSpanId(null)
                    .rootKind(kind != null ? kind.name() : null)
                    .build();
        }

        @Override
        public TraceContext startSpan(AgentSession session, TraceContext parentContext, String spanName,
                TraceSpanKind kind, Instant startedAt, Map<String, Object> attributes) {
            TraceRecord traceRecord = findTrace(session, parentContext.getTraceId());
            String spanId = UUID.randomUUID().toString();
            TraceSpanRecord spanRecord = TraceSpanRecord.builder()
                    .spanId(spanId)
                    .parentSpanId(parentContext.getSpanId())
                    .name(spanName)
                    .kind(kind)
                    .startedAt(startedAt)
                    .attributes(copyAttributes(attributes))
                    .build();
            traceRecord.getSpans().add(spanRecord);
            return TraceContext.builder()
                    .traceId(parentContext.getTraceId())
                    .spanId(spanId)
                    .parentSpanId(parentContext.getSpanId())
                    .rootKind(parentContext.getRootKind())
                    .build();
        }

        @Override
        public void captureSnapshot(AgentSession session, TraceContext spanContext,
                RuntimeConfig.TracingConfig tracingConfig, String role, String contentType, byte[] payload) {
            // No-op for tests that only assert span/event contracts.
        }

        @Override
        public void finishSpan(AgentSession session, TraceContext traceContext, TraceStatusCode statusCode,
                String statusMessage, Instant endedAt) {
            TraceRecord traceRecord = findTrace(session, traceContext.getTraceId());
            TraceSpanRecord spanRecord = findSpan(traceRecord, traceContext.getSpanId());
            spanRecord.setStatusCode(statusCode);
            spanRecord.setStatusMessage(statusMessage);
            spanRecord.setEndedAt(endedAt);
            if (traceContext.getParentSpanId() == null) {
                traceRecord.setEndedAt(endedAt);
            }
        }

        @Override
        public void appendEvent(AgentSession session, TraceContext spanContext, String eventName,
                Instant timestamp, Map<String, Object> attributes) {
            TraceRecord traceRecord = findTrace(session, spanContext.getTraceId());
            TraceSpanRecord spanRecord = findSpan(traceRecord, spanContext.getSpanId());
            spanRecord.getEvents().add(TraceEventRecord.builder()
                    .name(eventName)
                    .timestamp(timestamp)
                    .attributes(copyAttributes(attributes))
                    .build());
        }

        private void ensureSessionTraceState(AgentSession session) {
            if (session.getTraces() == null) {
                session.setTraces(new ArrayList<>());
            }
            if (session.getTraceStorageStats() == null) {
                session.setTraceStorageStats(new TraceStorageStats());
            }
        }

        private TraceRecord findTrace(AgentSession session, String traceId) {
            return session.getTraces().stream()
                    .filter(traceRecord -> traceRecord != null && traceId.equals(traceRecord.getTraceId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Trace not found: " + traceId));
        }

        private TraceSpanRecord findSpan(TraceRecord traceRecord, String spanId) {
            return traceRecord.getSpans().stream()
                    .filter(spanRecord -> spanRecord != null && spanId.equals(spanRecord.getSpanId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Trace span not found: " + spanId));
        }

        private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
            return attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
        }
    }
}
