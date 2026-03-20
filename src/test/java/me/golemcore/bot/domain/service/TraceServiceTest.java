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
}
