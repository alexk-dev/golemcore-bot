package me.golemcore.bot.domain.model.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceStorageStats {
    @Builder.Default
    private Long compressedSnapshotBytes = 0L;
    @Builder.Default
    private Long uncompressedSnapshotBytes = 0L;
    @Builder.Default
    private Integer evictedSnapshots = 0;
    @Builder.Default
    private Integer evictedTraces = 0;
    @Builder.Default
    private Integer truncatedTraces = 0;
}
