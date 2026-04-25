package me.golemcore.bot.domain.model.hive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HivePolicyApplyResult {

    private String policyGroupId;
    private Integer targetVersion;
    private Integer appliedVersion;
    private String syncStatus;
    private String checksum;
    private String errorDigest;
    private String errorDetails;
}
