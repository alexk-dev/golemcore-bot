package me.golemcore.bot.domain.model.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceEventRecord {
    private String name;
    private Instant timestamp;
    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
