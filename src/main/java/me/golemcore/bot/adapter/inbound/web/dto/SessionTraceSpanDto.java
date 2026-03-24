package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceSpanDto {
    private String spanId;
    private String parentSpanId;
    private String name;
    private String kind;
    private String statusCode;
    private String statusMessage;
    private String startedAt;
    private String endedAt;
    private Long durationMs;
    private Map<String, Object> attributes;
    private List<EventDto> events;
    private List<SessionTraceSnapshotDto> snapshots;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventDto {
        private String name;
        private String timestamp;
        private Map<String, Object> attributes;
    }
}
