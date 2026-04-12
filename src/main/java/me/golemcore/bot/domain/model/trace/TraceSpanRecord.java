package me.golemcore.bot.domain.model.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceSpanRecord {
    private String spanId;
    private String parentSpanId;
    private String name;
    private TraceSpanKind kind;
    private TraceStatusCode statusCode;
    private String statusMessage;
    private Instant startedAt;
    private Instant endedAt;
    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
    @Builder.Default
    private List<TraceEventRecord> events = new ArrayList<>();
    @Builder.Default
    private List<TraceSnapshot> snapshots = new ArrayList<>();
}
