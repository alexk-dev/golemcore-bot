package me.golemcore.bot.adapter.inbound.web.dto;

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
    private List<FileTreeNodeDto> children;
}
