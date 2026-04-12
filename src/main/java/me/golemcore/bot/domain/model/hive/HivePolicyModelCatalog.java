package me.golemcore.bot.domain.model.hive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HivePolicyModelCatalog {

    private String defaultModel;

    @Builder.Default
    private Map<String, HivePolicyModelConfig> models = new LinkedHashMap<>();

    private HivePolicyModelConfig defaults;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HivePolicyModelConfig {
        private String provider;
        private String displayName;
        private Boolean supportsVision;
        private Boolean supportsTemperature;
        private Integer maxInputTokens;
        private HivePolicyReasoningConfig reasoning;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HivePolicyReasoningConfig {
        private String defaultLevel;
        @Builder.Default
        private Map<String, HivePolicyReasoningLevelConfig> levels = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HivePolicyReasoningLevelConfig {
        private Integer maxInputTokens;
    }
}
