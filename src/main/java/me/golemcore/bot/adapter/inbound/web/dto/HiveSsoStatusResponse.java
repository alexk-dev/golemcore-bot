package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HiveSsoStatusResponse {
    private boolean enabled;
    private boolean available;
    private String loginUrl;
    private String reason;
}
