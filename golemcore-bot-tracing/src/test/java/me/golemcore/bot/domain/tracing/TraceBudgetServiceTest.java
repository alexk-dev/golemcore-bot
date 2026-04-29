package me.golemcore.bot.domain.tracing;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceBudgetServiceTest {

    @Test
    void shouldEvictOldestSnapshotsWhenBudgetExceeded() {
        TraceBudgetService service = new TraceBudgetService();
        AgentSession session = AgentSession.builder().messages(new ArrayList<>())
                .metadata(new java.util.LinkedHashMap<>())
                .traces(new ArrayList<>(
                        List.of(createTrace("trace-1", "span-1", Instant.parse("2026-03-20T10:00:00Z"), 40L),
                                createTrace("trace-2", "span-2", Instant.parse("2026-03-20T10:01:00Z"), 30L))))
                .traceStorageStats(TraceStorageStats.builder().compressedSnapshotBytes(70L)
                        .uncompressedSnapshotBytes(140L).build())
                .build();

        service.enforceBudget(session, 35L);

        assertTrue(session.getTraces().get(0).isTruncated());
        assertEquals(0, session.getTraces().get(0).getSpans().get(0).getSnapshots().size());
        assertEquals(1, session.getTraces().get(1).getSpans().get(0).getSnapshots().size());
        assertEquals(1, session.getTraceStorageStats().getEvictedSnapshots());
    }

    @Test
    void shouldEvictOldestTracesWhenTraceCountLimitIsExceeded() {
        TraceBudgetService service = new TraceBudgetService();
        AgentSession session = AgentSession.builder().messages(new ArrayList<>())
                .metadata(new java.util.LinkedHashMap<>())
                .traces(new ArrayList<>(
                        List.of(createTrace("trace-1", "span-1", Instant.parse("2026-03-20T10:00:00Z"), 40L),
                                createTrace("trace-2", "span-2", Instant.parse("2026-03-20T10:01:00Z"), 30L),
                                createTrace("trace-3", "span-3", Instant.parse("2026-03-20T10:02:00Z"), 20L))))
                .traceStorageStats(TraceStorageStats.builder().compressedSnapshotBytes(90L)
                        .uncompressedSnapshotBytes(180L).build())
                .build();

        service.enforceTraceCountLimit(session, 2);

        assertEquals(2, session.getTraces().size());
        assertEquals("trace-2", session.getTraces().get(0).getTraceId());
        assertEquals("trace-3", session.getTraces().get(1).getTraceId());
        assertEquals(1, session.getTraceStorageStats().getEvictedTraces());
        assertEquals(50L, session.getTraceStorageStats().getCompressedSnapshotBytes());
        assertEquals(100L, session.getTraceStorageStats().getUncompressedSnapshotBytes());
    }

    private TraceRecord createTrace(String traceId, String spanId, Instant startedAt, long compressedSize) {
        TraceSnapshot snapshot = TraceSnapshot.builder().snapshotId(traceId + "-snapshot").role("request")
                .contentType("application/json").encoding("zstd").compressedPayload(new byte[] { 1, 2, 3 })
                .originalSize(compressedSize * 2).compressedSize(compressedSize).truncated(false).build();
        TraceSpanRecord span = TraceSpanRecord.builder().spanId(spanId).name("tool.call").kind(TraceSpanKind.TOOL)
                .statusCode(TraceStatusCode.OK).startedAt(startedAt).endedAt(startedAt.plusMillis(5))
                .events(new ArrayList<>()).attributes(new java.util.LinkedHashMap<>())
                .snapshots(new ArrayList<>(List.of(snapshot))).build();
        return TraceRecord.builder().traceId(traceId).rootSpanId(spanId).traceName("trace").startedAt(startedAt)
                .endedAt(startedAt.plusMillis(10)).spans(new ArrayList<>(List.of(span)))
                .compressedSnapshotBytes(compressedSize).uncompressedSnapshotBytes(compressedSize * 2).build();
    }
}
