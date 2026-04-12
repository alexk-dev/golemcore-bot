package me.golemcore.bot.domain.view;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceSpanView {
    private String spanId;
    private String parentSpanId;
    private String name;
    private String kind;
    private String statusCode;
    private String statusMessage;
    private Instant startedAt;
    private Instant endedAt;
    private Long durationMs;
    private Map<String, Object> attributes;
    private List<EventView> events;
    private List<SessionTraceSnapshotView> snapshots;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventView {
        private String name;
        private Instant timestamp;
        private Map<String, Object> attributes;
    }
}
