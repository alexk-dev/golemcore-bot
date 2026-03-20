package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceRecord;
import me.golemcore.bot.domain.model.trace.TraceSnapshot;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceSpanRecord;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.model.trace.TraceStorageStats;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TraceService {

    private static final String ZSTD = "zstd";
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final TraceSnapshotCompressionService compressionService;
    private final TraceBudgetService traceBudgetService;

    public TraceService(TraceSnapshotCompressionService compressionService, TraceBudgetService traceBudgetService) {
        this.compressionService = compressionService;
        this.traceBudgetService = traceBudgetService;
    }

    public TraceContext startRootTrace(AgentSession session, String traceName, TraceSpanKind kind, Instant startedAt,
            Map<String, Object> attributes) {
        ensureSessionTraceState(session);
        String traceId = UUID.randomUUID().toString();
        String spanId = UUID.randomUUID().toString();
        TraceSpanRecord rootSpan = TraceSpanRecord.builder()
                .spanId(spanId)
                .name(traceName)
                .kind(kind)
                .startedAt(startedAt)
                .endedAt(null)
                .attributes(copyAttributes(attributes))
                .events(new ArrayList<>())
                .snapshots(new ArrayList<>())
                .build();
        TraceRecord traceRecord = TraceRecord.builder()
                .traceId(traceId)
                .rootSpanId(spanId)
                .traceName(traceName)
                .startedAt(startedAt)
                .spans(new ArrayList<>())
                .truncated(false)
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

    public TraceContext startSpan(AgentSession session, TraceContext parentContext, String spanName, TraceSpanKind kind,
            Instant startedAt, Map<String, Object> attributes) {
        TraceRecord trace = findTrace(session, parentContext.getTraceId());
        String spanId = UUID.randomUUID().toString();
        TraceSpanRecord spanRecord = TraceSpanRecord.builder()
                .spanId(spanId)
                .parentSpanId(parentContext.getSpanId())
                .name(spanName)
                .kind(kind)
                .startedAt(startedAt)
                .attributes(copyAttributes(attributes))
                .events(new ArrayList<>())
                .snapshots(new ArrayList<>())
                .build();
        trace.getSpans().add(spanRecord);
        return TraceContext.builder()
                .traceId(parentContext.getTraceId())
                .spanId(spanId)
                .parentSpanId(parentContext.getSpanId())
                .rootKind(parentContext.getRootKind())
                .build();
    }

    public void captureSnapshot(AgentSession session, TraceContext spanContext,
            RuntimeConfig.TracingConfig tracingConfig,
            String role, String contentType, byte[] payload) {
        if (session == null || spanContext == null || tracingConfig == null) {
            return;
        }
        if (!Boolean.TRUE.equals(tracingConfig.getEnabled())
                || !Boolean.TRUE.equals(tracingConfig.getPayloadSnapshotsEnabled())) {
            return;
        }
        byte[] rawPayload = payload != null ? payload : new byte[0];
        byte[] compressedPayload = compressionService.compress(rawPayload);
        TraceSnapshot snapshot = TraceSnapshot.builder()
                .snapshotId(UUID.randomUUID().toString())
                .role(role)
                .contentType(contentType)
                .encoding(ZSTD)
                .compressedPayload(compressedPayload)
                .originalSize((long) rawPayload.length)
                .compressedSize((long) compressedPayload.length)
                .truncated(false)
                .build();

        TraceRecord trace = findTrace(session, spanContext.getTraceId());
        TraceSpanRecord span = findSpan(trace, spanContext.getSpanId());
        span.getSnapshots().add(snapshot);
        trace.setCompressedSnapshotBytes(safeLong(trace.getCompressedSnapshotBytes()) + compressedPayload.length);
        trace.setUncompressedSnapshotBytes(safeLong(trace.getUncompressedSnapshotBytes()) + rawPayload.length);

        TraceStorageStats stats = session.getTraceStorageStats();
        stats.setCompressedSnapshotBytes(safeLong(stats.getCompressedSnapshotBytes()) + compressedPayload.length);
        stats.setUncompressedSnapshotBytes(safeLong(stats.getUncompressedSnapshotBytes()) + rawPayload.length);

        int budgetMb = tracingConfig.getSessionTraceBudgetMb() != null ? tracingConfig.getSessionTraceBudgetMb() : 128;
        traceBudgetService.enforceBudget(session, budgetMb * BYTES_PER_MB);
    }

    public void finishSpan(AgentSession session, TraceContext traceContext, TraceStatusCode statusCode,
            String statusMessage, Instant endedAt) {
        if (session == null || traceContext == null) {
            return;
        }
        TraceRecord trace = findTrace(session, traceContext.getTraceId());
        TraceSpanRecord span = findSpan(trace, traceContext.getSpanId());
        span.setStatusCode(statusCode);
        span.setStatusMessage(statusMessage);
        span.setEndedAt(endedAt);
        if (traceContext.getParentSpanId() == null) {
            trace.setEndedAt(endedAt);
        }
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
        if (session == null || session.getTraces() == null) {
            throw new IllegalArgumentException("Trace session is not initialized");
        }
        return session.getTraces().stream()
                .filter(traceRecord -> traceRecord != null && traceId.equals(traceRecord.getTraceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Trace not found: " + traceId));
    }

    private TraceSpanRecord findSpan(TraceRecord trace, String spanId) {
        if (trace == null || trace.getSpans() == null) {
            throw new IllegalArgumentException("Trace span collection is not initialized");
        }
        return trace.getSpans().stream()
                .filter(spanRecord -> spanRecord != null && spanId.equals(spanRecord.getSpanId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Trace span not found: " + spanId));
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>();
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }
}
