package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InlineEditResponse {
    private String path;
    private String replacement;
}
