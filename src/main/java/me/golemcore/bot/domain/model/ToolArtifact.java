package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolArtifact {

    private String path;
    private String filename;
    private String mimeType;
    private long size;
    private String downloadUrl;
}
