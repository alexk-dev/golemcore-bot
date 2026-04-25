package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceSummaryDto {
    private String sessionId;
    private Integer traceCount;
    private Integer spanCount;
    private Integer snapshotCount;
    private SessionTraceStorageStatsDto storageStats;
    private List<TraceSummaryDto> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceSummaryDto {
        private String traceId;
        private String rootSpanId;
        private String traceName;
        private String rootKind;
        private String rootStatusCode;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private Integer spanCount;
        private Integer snapshotCount;
        private boolean truncated;
    }
}
