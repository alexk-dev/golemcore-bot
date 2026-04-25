package me.golemcore.bot.domain.model.hive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HiveCapabilitySnapshot {

    @Builder.Default
    private Set<String> providers = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> modelFamilies = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> enabledTools = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> enabledAutonomyFeatures = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> capabilityTags = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> supportedChannels = new LinkedHashSet<>();

    private String snapshotHash;
    private String defaultModel;
}
