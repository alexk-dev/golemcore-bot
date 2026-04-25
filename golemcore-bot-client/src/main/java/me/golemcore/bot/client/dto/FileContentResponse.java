package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileContentResponse {
    private String path;
    private String content;
    private long size;
    private String updatedAt;
    private String mimeType;
    private boolean binary;
    private boolean image;
    private boolean editable;
    private String downloadUrl;
}
