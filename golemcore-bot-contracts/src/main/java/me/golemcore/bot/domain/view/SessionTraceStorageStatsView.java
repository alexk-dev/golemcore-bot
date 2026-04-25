package me.golemcore.bot.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceStorageStatsView {
    private Long compressedSnapshotBytes;
    private Long uncompressedSnapshotBytes;
    private Integer evictedSnapshots;
    private Integer evictedTraces;
    private Integer truncatedTraces;
}
