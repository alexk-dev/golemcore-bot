package me.golemcore.bot.domain.view;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceView {
    private String sessionId;
    private SessionTraceStorageStatsView storageStats;
    private List<TraceView> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceView {
        private String traceId;
        private String rootSpanId;
        private String traceName;
        private String startedAt;
        private String endedAt;
        private boolean truncated;
        private Long compressedSnapshotBytes;
        private Long uncompressedSnapshotBytes;
        private List<SessionTraceSpanView> spans;
    }
}
