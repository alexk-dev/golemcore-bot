package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptCreateRequest {
    private String name;
    private String description;
    private int order;
    private boolean enabled;
    private String content;
}
