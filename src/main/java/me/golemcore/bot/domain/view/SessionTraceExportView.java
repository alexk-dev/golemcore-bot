package me.golemcore.bot.domain.view;

import java.time.Instant;
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
public class SessionTraceExportView {
    private String sessionId;
    private SessionTraceStorageStatsView storageStats;
    private List<TraceExportView> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceExportView {
        private String traceId;
        private String rootSpanId;
        private String traceName;
        private Instant startedAt;
        private Instant endedAt;
        private boolean truncated;
        private Long compressedSnapshotBytes;
        private Long uncompressedSnapshotBytes;
        private List<SpanExportView> spans;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpanExportView {
        private String spanId;
        private String parentSpanId;
        private String name;
        private String kind;
        private StatusView status;
        private Instant startedAt;
        private Instant endedAt;
        private Long durationMs;
        private Map<String, Object> attributes;
        private List<EventExportView> events;
        private List<SnapshotExportView> snapshots;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusView {
        private String code;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventExportView {
        private String name;
        private Instant timestamp;
        private Map<String, Object> attributes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotExportView {
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
