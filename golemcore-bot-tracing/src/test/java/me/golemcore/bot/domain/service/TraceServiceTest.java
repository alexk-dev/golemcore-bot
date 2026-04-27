package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceServiceTest {

    private TraceService createService() {
        return new TraceService(new TraceSnapshotCompressionService(), new TraceBudgetService());
    }

    private AgentSession createSessionWithTraceState() {
        return AgentSession.builder().messages(new ArrayList<>()).metadata(new LinkedHashMap<>())
                .traces(new ArrayList<>()).traceStorageStats(TraceStorageStats.builder().build()).build();
    }

    @Test
    void shouldNotCaptureSnapshotsWhenPayloadSnapshotsDisabled() {
        TraceService service = createService();
        AgentSession session = createSessionWithTraceState();
        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder().enabled(true)
                .payloadSnapshotsEnabled(false).sessionTraceBudgetMb(128).build();

        TraceContext root = service.startRootTrace(session, "web.request", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"), Map.of("session.id", "sess-1"));
        TraceContext child = service.startSpan(session, root, "llm.call", TraceSpanKind.LLM,
                Instant.parse("2026-03-20T12:00:01Z"), Map.of("provider", "openai"));

        service.captureSnapshot(session, child, tracingConfig, "request", "application/json",
                "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8));
        service.finishSpan(session, child, TraceStatusCode.OK, null, Instant.parse("2026-03-20T12:00:02Z"));
        service.finishSpan(session, root, TraceStatusCode.OK, null, Instant.parse("2026-03-20T12:00:03Z"));

        assertEquals(1, session.getTraces().size());
        assertEquals(2, session.getTraces().get(0).getSpans().size());
        assertTrue(session.getTraces().get(0).getSpans().stream().allMatch(span -> span.getSnapshots().isEmpty()));
    }

    @Test
    void shouldPreserveOriginalPayloadSizeWhenSnapshotIsTruncated() {
        TraceService service = createService();
        AgentSession session = createSessionWithTraceState();
        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder().enabled(true)
                .payloadSnapshotsEnabled(true).sessionTraceBudgetMb(128).maxSnapshotSizeKb(1).maxSnapshotsPerSpan(10)
                .build();
        byte[] payload = "x".repeat(2048).getBytes(StandardCharsets.UTF_8);

        TraceContext root = service.startRootTrace(session, "web.request", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"), Map.of("session.id", "sess-1"));

        service.captureSnapshot(session, root, tracingConfig, "request", "application/json", payload);

        assertEquals(1, session.getTraces().size());
        assertEquals(1, session.getTraces().get(0).getSpans().size());
        assertEquals(1, session.getTraces().get(0).getSpans().get(0).getSnapshots().size());
        assertTrue(session.getTraces().get(0).isTruncated());
        assertTrue(session.getTraces().get(0).getSpans().get(0).getSnapshots().get(0).isTruncated());
        assertEquals(payload.length,
                session.getTraces().get(0).getSpans().get(0).getSnapshots().get(0).getOriginalSize());
        assertEquals(payload.length, session.getTraces().get(0).getUncompressedSnapshotBytes());
        assertEquals(payload.length, session.getTraceStorageStats().getUncompressedSnapshotBytes());
    }

    @Test
    void shouldEvictOldestTraceWhenTraceCountLimitIsExceeded() {
        TraceService service = createService();
        AgentSession session = createSessionWithTraceState();

        service.startRootTrace(session, "trace-1", TraceSpanKind.INGRESS, Instant.parse("2026-03-20T12:00:00Z"),
                Map.of("session.id", "sess-1"), 2);
        service.startRootTrace(session, "trace-2", TraceSpanKind.INGRESS, Instant.parse("2026-03-20T12:00:01Z"),
                Map.of("session.id", "sess-1"), 2);
        TraceContext newest = service.startRootTrace(session, "trace-3", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:02Z"), Map.of("session.id", "sess-1"), 2);

        assertEquals(2, session.getTraces().size());
        assertEquals("trace-2", session.getTraces().get(0).getTraceName());
        assertEquals("trace-3", session.getTraces().get(1).getTraceName());
        assertEquals(1, session.getTraceStorageStats().getEvictedTraces());
        assertNotNull(newest);
        assertEquals("trace-3",
                session.getTraces().stream().filter(trace -> newest.getTraceId().equals(trace.getTraceId())).findFirst()
                        .orElseThrow().getTraceName());
    }

    @Test
    void shouldInitializeMissingTraceCollectionsAndReuseExistingSeededRootTrace() {
        TraceService service = createService();
        AgentSession session = AgentSession.builder().messages(new ArrayList<>()).metadata(new LinkedHashMap<>())
                .traces(null).traceStorageStats(null).build();
        TraceContext seeded = TraceContext.builder().traceId("trace-seeded").spanId("span-seeded")
                .rootKind(TraceSpanKind.INGRESS.name()).build();

        TraceContext created = service.startRootTrace(session, seeded, "telegram.message", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"), null, 10);
        TraceContext reused = service.startRootTrace(session, seeded, "telegram.message", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:01Z"), Map.of(), 10);

        assertNotNull(session.getTraces());
        assertNotNull(session.getTraceStorageStats());
        assertEquals(1, session.getTraces().size());
        assertEquals("trace-seeded", created.getTraceId());
        assertEquals("span-seeded", created.getSpanId());
        assertEquals("trace-seeded", reused.getTraceId());
        assertEquals("span-seeded", reused.getSpanId());
        assertEquals(TraceSpanKind.INGRESS.name(), reused.getRootKind());
        assertSame(session.getTraces().get(0), session.getTraces().stream()
                .filter(trace -> "trace-seeded".equals(trace.getTraceId())).findFirst().orElseThrow());
    }

    @Test
    void shouldRespectSnapshotLimitAndInitializeMissingEventCollection() {
        TraceService service = createService();
        AgentSession session = createSessionWithTraceState();
        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder().enabled(true)
                .payloadSnapshotsEnabled(true).sessionTraceBudgetMb(null).maxSnapshotSizeKb(null).maxSnapshotsPerSpan(1)
                .build();

        TraceContext root = service.startRootTrace(session, "telegram.message", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"), Map.of());

        service.captureSnapshot(session, root, tracingConfig, "request", "application/json", null);
        service.captureSnapshot(session, root, tracingConfig, "response", "application/json",
                "second".getBytes(StandardCharsets.UTF_8));
        session.getTraces().get(0).getSpans().get(0).setEvents(null);
        service.appendEvent(session, root, "llm.request", Instant.parse("2026-03-20T12:00:02Z"), Map.of("attempt", 1));

        assertEquals(1, session.getTraces().get(0).getSpans().get(0).getSnapshots().size());
        assertTrue(session.getTraces().get(0).isTruncated());
        assertEquals(1, session.getTraces().get(0).getSpans().get(0).getEvents().size());
        assertEquals("llm.request", session.getTraces().get(0).getSpans().get(0).getEvents().get(0).getName());
    }

    @Test
    void shouldFinishRootSpanAndHandleTraceGuards() {
        TraceService service = createService();
        AgentSession session = createSessionWithTraceState();
        TraceContext root = service.startRootTrace(session, "web.request", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"), Map.of("session.id", "sess-1"));

        service.finishSpan(session, root, TraceStatusCode.OK, "done", Instant.parse("2026-03-20T12:00:03Z"));

        assertEquals(Instant.parse("2026-03-20T12:00:03Z"), session.getTraces().get(0).getEndedAt());
        assertDoesNotThrow(() -> service.finishSpan(null, root, TraceStatusCode.OK, null, Instant.now()));
        assertDoesNotThrow(() -> service.captureSnapshot(null, root, null, "request", "application/json", null));
        assertDoesNotThrow(() -> service.appendEvent(session, root, " ", Instant.now(), Map.of()));
    }

    @Test
    void shouldFailFastWhenTraceOrSpanIsMissing() {
        TraceService service = createService();
        AgentSession session = createSessionWithTraceState();
        TraceContext root = service.startRootTrace(session, "web.request", TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"), Map.of("session.id", "sess-1"));

        assertThrows(IllegalArgumentException.class,
                () -> service.startSpan(session,
                        TraceContext.builder().traceId("missing").spanId(root.getSpanId()).rootKind(root.getRootKind())
                                .build(),
                        "llm.call", TraceSpanKind.LLM, Instant.parse("2026-03-20T12:00:01Z"), Map.of()));

        session.getTraces().get(0).setSpans(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.appendEvent(session, root, "llm.request", Instant.now(), Map.of()));
        assertEquals(0L, session.getTraceStorageStats().getCompressedSnapshotBytes());
    }
}
