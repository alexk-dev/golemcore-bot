package me.golemcore.bot.adapter.inbound.web.dto.selfevolving.tactic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Search status and degradation metadata for the tactic workspace.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelfEvolvingTacticSearchStatusDto {

    private String mode;
    private String reason;
    private String provider;
    private String model;
    private Boolean degraded;
    private String runtimeState;
    private Boolean owned;
    private Boolean runtimeInstalled;
    private Boolean runtimeHealthy;
    private String runtimeVersion;
    private String baseUrl;
    private Boolean modelAvailable;
    private Integer restartAttempts;
    private String nextRetryAt;
    private String nextRetryTime;
    private Boolean autoInstallConfigured;
    private Boolean pullOnStartConfigured;
    private Boolean pullAttempted;
    private Boolean pullSucceeded;
    private String updatedAt;
}
