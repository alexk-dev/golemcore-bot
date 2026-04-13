package me.golemcore.bot.domain.model.hive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HivePolicyBindingState {

    @Builder.Default
    private int schemaVersion = 1;

    private String policyGroupId;
    private Integer targetVersion;
    private Integer appliedVersion;
    private String checksum;
    private String syncStatus;
    private Instant lastSyncRequestedAt;
    private Instant lastAppliedAt;
    private String lastErrorDigest;
    private Instant lastErrorAt;

    public boolean hasActiveBinding() {
        return policyGroupId != null && !policyGroupId.isBlank();
    }
}
