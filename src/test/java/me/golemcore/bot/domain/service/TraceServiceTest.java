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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceServiceTest {

    @Test
    void shouldNotCaptureSnapshotsWhenPayloadSnapshotsDisabled() {
        TraceService service = new TraceService(
                new TraceSnapshotCompressionService(),
                new TraceBudgetService());
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .traces(new ArrayList<>())
                .traceStorageStats(TraceStorageStats.builder().build())
                .build();
        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder()
                .enabled(true)
                .payloadSnapshotsEnabled(false)
                .sessionTraceBudgetMb(128)
                .build();

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
        TraceService service = new TraceService(
                new TraceSnapshotCompressionService(),
                new TraceBudgetService());
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .traces(new ArrayList<>())
                .traceStorageStats(TraceStorageStats.builder().build())
                .build();
        RuntimeConfig.TracingConfig tracingConfig = RuntimeConfig.TracingConfig.builder()
                .enabled(true)
                .payloadSnapshotsEnabled(true)
                .sessionTraceBudgetMb(128)
                .maxSnapshotSizeKb(1)
                .maxSnapshotsPerSpan(10)
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
        TraceService service = new TraceService(
                new TraceSnapshotCompressionService(),
                new TraceBudgetService());
        AgentSession session = AgentSession.builder()
                .messages(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .traces(new ArrayList<>())
                .traceStorageStats(TraceStorageStats.builder().build())
                .build();

        service.startRootTrace(
                session,
                "trace-1",
                TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:00Z"),
                Map.of("session.id", "sess-1"),
                2);
        service.startRootTrace(
                session,
                "trace-2",
                TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:01Z"),
                Map.of("session.id", "sess-1"),
                2);
        TraceContext newest = service.startRootTrace(
                session,
                "trace-3",
                TraceSpanKind.INGRESS,
                Instant.parse("2026-03-20T12:00:02Z"),
                Map.of("session.id", "sess-1"),
                2);

        assertEquals(2, session.getTraces().size());
        assertEquals("trace-2", session.getTraces().get(0).getTraceName());
        assertEquals("trace-3", session.getTraces().get(1).getTraceName());
        assertEquals(1, session.getTraceStorageStats().getEvictedTraces());
        assertNotNull(newest);
        assertEquals("trace-3", session.getTraces().stream()
                .filter(trace -> newest.getTraceId().equals(trace.getTraceId()))
                .findFirst()
                .orElseThrow()
                .getTraceName());
    }
}
