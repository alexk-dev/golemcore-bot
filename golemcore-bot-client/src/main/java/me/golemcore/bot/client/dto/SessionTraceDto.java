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
public class SessionTraceDto {
    private String sessionId;
    private SessionTraceStorageStatsDto storageStats;
    private List<TraceDto> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceDto {
        private String traceId;
        private String rootSpanId;
        private String traceName;
        private String startedAt;
        private String endedAt;
        private boolean truncated;
        private Long compressedSnapshotBytes;
        private Long uncompressedSnapshotBytes;
        private List<SessionTraceSpanDto> spans;
    }
}
