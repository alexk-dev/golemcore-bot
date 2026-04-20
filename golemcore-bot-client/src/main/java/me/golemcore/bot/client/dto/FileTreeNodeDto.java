package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileTreeNodeDto {
    private String path;
    private String name;
    private String type;
    private Long size;
    private String mimeType;
    private String updatedAt;
    private boolean binary;
    private boolean image;
    private boolean editable;
    private boolean hasChildren;
    private List<FileTreeNodeDto> children;
}
