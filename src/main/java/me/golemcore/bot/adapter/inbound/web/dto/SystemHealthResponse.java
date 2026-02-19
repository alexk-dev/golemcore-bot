package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemHealthResponse {
    private String status;
    private String version;
    private String gitCommit;
    private String buildTime;
    private long uptimeMs;
    private Map<String, ChannelStatus> channels;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelStatus {
        private String type;
        private boolean running;
        private boolean enabled;
    }
}
