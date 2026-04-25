package me.golemcore.bot.domain.model.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceRecord {
    private String traceId;
    private String rootSpanId;
    private String traceName;
    private Instant startedAt;
    private Instant endedAt;
    @Builder.Default
    private List<TraceSpanRecord> spans = new ArrayList<>();
    private boolean truncated;
    private Long compressedSnapshotBytes;
    private Long uncompressedSnapshotBytes;
}
