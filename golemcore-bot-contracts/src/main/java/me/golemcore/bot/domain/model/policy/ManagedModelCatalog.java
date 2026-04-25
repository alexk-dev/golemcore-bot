package me.golemcore.bot.domain.model.policy;

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
public class ManagedModelCatalog {

    private String defaultModel;

    @Builder.Default
    private Map<String, ManagedModelConfig> models = new LinkedHashMap<>();

    private ManagedModelConfig defaults;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManagedModelConfig {
        private String provider;
        private String displayName;
        private Boolean supportsVision;
        private Boolean supportsTemperature;
        private Integer maxInputTokens;
        private ManagedReasoningConfig reasoning;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManagedReasoningConfig {
        private String defaultLevel;
        @Builder.Default
        private Map<String, ManagedReasoningLevelConfig> levels = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManagedReasoningLevelConfig {
        private Integer maxInputTokens;
    }
}
