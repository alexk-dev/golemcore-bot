package me.golemcore.bot.domain.view;

import java.util.List;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceSummaryView {
    private String sessionId;
    private Integer traceCount;
    private Integer spanCount;
    private Integer snapshotCount;
    private SessionTraceStorageStatsView storageStats;
    private List<TraceSummaryView> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceSummaryView {
        private String traceId;
        private String rootSpanId;
        private String traceName;
        private String rootKind;
        private String rootStatusCode;
        private Instant startedAt;
        private Instant endedAt;
        private Long durationMs;
        private Integer spanCount;
        private Integer snapshotCount;
        private boolean truncated;
    }
}
