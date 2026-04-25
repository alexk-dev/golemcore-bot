package me.golemcore.bot.domain.model.hive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.policy.ManagedModelCatalog;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class HivePolicyPackage {

    private String policyGroupId;
    private Integer targetVersion;
    private String checksum;

    @Builder.Default
    private Map<String, RuntimeConfig.LlmProviderConfig> llmProviders = new LinkedHashMap<>();

    @Builder.Default
    private RuntimeConfig.ModelRouterConfig modelRouter = RuntimeConfig.ModelRouterConfig.builder().build();

    @Builder.Default
    private ManagedModelCatalog modelCatalog = ManagedModelCatalog.builder().build();

    public RuntimeConfig.LlmConfig toLlmConfig() {
        return RuntimeConfig.LlmConfig.builder()
                .providers(llmProviders != null ? new LinkedHashMap<>(llmProviders) : new LinkedHashMap<>())
                .build();
    }
}
