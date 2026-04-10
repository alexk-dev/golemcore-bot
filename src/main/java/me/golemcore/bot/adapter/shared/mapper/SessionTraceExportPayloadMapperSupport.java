package me.golemcore.bot.adapter.shared.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.adapter.shared.dto.SessionTraceExportPayload;
import me.golemcore.bot.domain.view.SessionTraceExportView;
import me.golemcore.bot.domain.view.SessionTraceStorageStatsView;

public final class SessionTraceExportPayloadMapperSupport {

    private SessionTraceExportPayloadMapperSupport() {
    }

    public static SessionTraceExportPayload toPayload(SessionTraceExportView view) {
        return SessionTraceExportPayload.builder()
                .sessionId(view.getSessionId())
                .storageStats(toStorageStatsPayload(view.getStorageStats()))
                .traces(view.getTraces().stream().map(SessionTraceExportPayloadMapperSupport::toTracePayload).toList())
                .build();
    }

    private static SessionTraceExportPayload.StorageStatsPayload toStorageStatsPayload(
            SessionTraceStorageStatsView view) {
        return SessionTraceExportPayload.StorageStatsPayload.builder()
                .compressedSnapshotBytes(view.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(view.getUncompressedSnapshotBytes())
                .evictedSnapshots(view.getEvictedSnapshots())
                .evictedTraces(view.getEvictedTraces())
                .truncatedTraces(view.getTruncatedTraces())
                .build();
    }

    private static SessionTraceExportPayload.TracePayload toTracePayload(SessionTraceExportView.TraceExportView view) {
        return SessionTraceExportPayload.TracePayload.builder()
                .traceId(view.getTraceId())
                .rootSpanId(view.getRootSpanId())
                .traceName(view.getTraceName())
                .startedAt(toTimestamp(view.getStartedAt()))
                .endedAt(toTimestamp(view.getEndedAt()))
                .truncated(view.isTruncated())
                .compressedSnapshotBytes(view.getCompressedSnapshotBytes())
                .uncompressedSnapshotBytes(view.getUncompressedSnapshotBytes())
                .spans(view.getSpans().stream().map(SessionTraceExportPayloadMapperSupport::toSpanPayload).toList())
                .build();
    }

    private static SessionTraceExportPayload.SpanPayload toSpanPayload(SessionTraceExportView.SpanExportView view) {
        return SessionTraceExportPayload.SpanPayload.builder()
                .spanId(view.getSpanId())
                .parentSpanId(view.getParentSpanId())
                .name(view.getName())
                .kind(view.getKind())
                .status(toStatusPayload(view.getStatus()))
                .startedAt(toTimestamp(view.getStartedAt()))
                .endedAt(toTimestamp(view.getEndedAt()))
                .durationMs(view.getDurationMs())
                .attributes(copyAttributes(view.getAttributes()))
                .events(view.getEvents().stream().map(SessionTraceExportPayloadMapperSupport::toEventPayload).toList())
                .snapshots(view.getSnapshots().stream()
                        .map(SessionTraceExportPayloadMapperSupport::toSnapshotPayload)
                        .toList())
                .build();
    }

    private static SessionTraceExportPayload.StatusPayload toStatusPayload(SessionTraceExportView.StatusView view) {
        if (view == null) {
            return SessionTraceExportPayload.StatusPayload.builder().build();
        }
        return SessionTraceExportPayload.StatusPayload.builder()
                .code(view.getCode())
                .message(view.getMessage())
                .build();
    }

    private static SessionTraceExportPayload.EventPayload toEventPayload(SessionTraceExportView.EventExportView view) {
        return SessionTraceExportPayload.EventPayload.builder()
                .name(view.getName())
                .timestamp(toTimestamp(view.getTimestamp()))
                .attributes(copyAttributes(view.getAttributes()))
                .build();
    }

    private static SessionTraceExportPayload.SnapshotPayload toSnapshotPayload(
            SessionTraceExportView.SnapshotExportView view) {
        return SessionTraceExportPayload.SnapshotPayload.builder()
                .snapshotId(view.getSnapshotId())
                .role(view.getRole())
                .contentType(view.getContentType())
                .encoding(view.getEncoding())
                .originalSize(view.getOriginalSize())
                .compressedSize(view.getCompressedSize())
                .truncated(view.isTruncated())
                .payloadText(view.getPayloadText())
                .build();
    }

    private static Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes != null ? new LinkedHashMap<>(attributes) : Map.of();
    }

    private static String toTimestamp(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
