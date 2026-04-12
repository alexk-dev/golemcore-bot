package me.golemcore.bot.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionTraceSnapshotView {
    private String snapshotId;
    private String role;
    private String contentType;
    private String encoding;
    private Long originalSize;
    private Long compressedSize;
    private boolean truncated;
    private boolean payloadAvailable;
    private String payloadPreview;
    private boolean payloadPreviewTruncated;
}
