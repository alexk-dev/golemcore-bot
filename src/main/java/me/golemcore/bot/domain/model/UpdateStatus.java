package me.golemcore.bot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateStatus {
    private UpdateState state;
    private boolean enabled;
    private boolean autoEnabled;
    private boolean maintenanceWindowEnabled;
    private String maintenanceWindowStartUtc;
    private String maintenanceWindowEndUtc;
    private String serverTimezone;
    private boolean windowOpen;
    private boolean busy;
    private UpdateBlockedReason blockedReason;
    private Instant nextEligibleAt;
    private UpdateVersionInfo current;
    private UpdateVersionInfo target;
    private UpdateVersionInfo staged;
    private UpdateVersionInfo available;
    private Instant lastCheckAt;
    private String lastError;
    private Integer progressPercent;
    private String stageTitle;
    private String stageDescription;
}
