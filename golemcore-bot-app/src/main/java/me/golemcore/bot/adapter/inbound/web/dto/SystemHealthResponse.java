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
    private SelfEvolvingEmbeddingsStatus selfEvolvingEmbeddings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelStatus {
        private String type;
        private boolean running;
        private boolean enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfEvolvingEmbeddingsStatus {
        private String mode;
        private String reason;
        private boolean degraded;
        private String runtimeState;
        private boolean owned;
        private boolean runtimeInstalled;
        private boolean runtimeHealthy;
        private String runtimeVersion;
        private String model;
        private boolean modelAvailable;
        private Integer restartAttempts;
        private String nextRetryTime;
    }
}
