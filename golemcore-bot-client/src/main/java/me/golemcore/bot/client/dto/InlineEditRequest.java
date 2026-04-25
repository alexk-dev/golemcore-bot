package me.golemcore.bot.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InlineEditRequest {
    private String path;
    private String content;
    private Integer selectionFrom;
    private Integer selectionTo;
    private String selectedText;
    private String instruction;
}
