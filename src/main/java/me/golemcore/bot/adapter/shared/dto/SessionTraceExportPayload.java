package me.golemcore.bot.adapter.shared.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceExportPayload {

    private String sessionId;
    private StorageStatsPayload storageStats;
    private List<TracePayload> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StorageStatsPayload {
        private Long compressedSnapshotBytes;
        private Long uncompressedSnapshotBytes;
        private Integer evictedSnapshots;
        private Integer evictedTraces;
        private Integer truncatedTraces;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TracePayload {
        private String traceId;
        private String rootSpanId;
        private String traceName;
        private String startedAt;
        private String endedAt;
        private boolean truncated;
        private Long compressedSnapshotBytes;
        private Long uncompressedSnapshotBytes;
        private List<SpanPayload> spans;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpanPayload {
        private String spanId;
        private String parentSpanId;
        private String name;
        private String kind;
        private StatusPayload status;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private Map<String, Object> attributes;
        private List<EventPayload> events;
        private List<SnapshotPayload> snapshots;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusPayload {
        private String code;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventPayload {
        private String name;
        private String timestamp;
        private Map<String, Object> attributes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotPayload {
        private String snapshotId;
        private String role;
        private String contentType;
        private String encoding;
        private Long originalSize;
        private Long compressedSize;
        private boolean truncated;
        private String payloadText;
    }
}
