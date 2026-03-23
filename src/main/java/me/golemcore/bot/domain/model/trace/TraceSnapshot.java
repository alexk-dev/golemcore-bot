package me.golemcore.bot.domain.model.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceSnapshot {
    private String snapshotId;
    private String role;
    private String contentType;
    private String encoding;
    private byte[] compressedPayload;
    private Long originalSize;
    private Long compressedSize;
    private boolean truncated;
}
