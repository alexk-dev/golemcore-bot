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
    private Boolean runtimeHealthy;
    private Boolean modelAvailable;
    private Boolean autoInstallConfigured;
    private Boolean pullOnStartConfigured;
    private Boolean pullAttempted;
    private Boolean pullSucceeded;
    private String updatedAt;
}
