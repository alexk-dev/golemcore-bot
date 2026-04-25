package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceStorageStatsDto {
    private Long compressedSnapshotBytes;
    private Long uncompressedSnapshotBytes;
    private Integer evictedSnapshots;
    private Integer evictedTraces;
    private Integer truncatedTraces;
}
