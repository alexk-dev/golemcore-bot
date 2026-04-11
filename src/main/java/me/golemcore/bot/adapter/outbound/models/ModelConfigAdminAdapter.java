package me.golemcore.bot.adapter.outbound.models;

import java.util.LinkedHashMap;
import java.util.Map;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import org.springframework.stereotype.Component;

@Component
public class ModelConfigAdminAdapter implements ModelConfigAdminPort {

    private final ModelConfigService modelConfigService;

    public ModelConfigAdminAdapter(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @Override
    public ModelsConfigSnapshot getConfig() {
        return toSnapshot(modelConfigService.getConfig());
    }

    @Override
    public ModelsConfigSnapshot replaceConfig(ModelsConfigSnapshot newConfig) {
        modelConfigService.replaceConfig(toModelsConfig(newConfig));
        return getConfig();
    }

    @Override
    public void saveModel(String id, String previousId, ModelSettingsSnapshot settings) {
        modelConfigService.saveModel(id, previousId, toModelSettings(settings));
    }

    @Override
    public boolean deleteModel(String id) {
        return modelConfigService.deleteModel(id);
    }

    @Override
    public void reload() {
        modelConfigService.reload();
    }

    private ModelsConfigSnapshot toSnapshot(ModelConfigService.ModelsConfig config) {
        return new ModelsConfigSnapshot(toSnapshotMap(config.getModels()), toSnapshot(config.getDefaults()));
    }

    private Map<String, ModelSettingsSnapshot> toSnapshotMap(Map<String, ModelConfigService.ModelSettings> models) {
        Map<String, ModelSettingsSnapshot> result = new LinkedHashMap<>();
        if (models == null) {
            return result;
        }
        models.forEach((key, value) -> result.put(key, toSnapshot(value)));
        return result;
    }

    private ModelSettingsSnapshot toSnapshot(ModelConfigService.ModelSettings settings) {
        if (settings == null) {
            return null;
        }
        return new ModelSettingsSnapshot(
                settings.getProvider(),
                settings.getDisplayName(),
                settings.isSupportsVision(),
                settings.isSupportsTemperature(),
                settings.getMaxInputTokens(),
                toReasoningSnapshot(settings.getReasoning()));
    }

    private ReasoningConfigSnapshot toReasoningSnapshot(ModelConfigService.ReasoningConfig reasoning) {
        if (reasoning == null) {
            return null;
        }
        Map<String, ReasoningLevelSnapshot> levels = new LinkedHashMap<>();
        if (reasoning.getLevels() != null) {
            reasoning.getLevels()
                    .forEach((key, value) -> levels.put(key, new ReasoningLevelSnapshot(value.getMaxInputTokens())));
        }
        return new ReasoningConfigSnapshot(reasoning.getDefaultLevel(), levels);
    }

    private ModelConfigService.ModelsConfig toModelsConfig(ModelsConfigSnapshot snapshot) {
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();
        config.setModels(new LinkedHashMap<>());
        if (snapshot != null && snapshot.models() != null) {
            snapshot.models().forEach((key, value) -> config.getModels().put(key, toModelSettings(value)));
        }
        config.setDefaults(toModelSettings(snapshot != null ? snapshot.defaults() : null));
        return config;
    }

    private ModelConfigService.ModelSettings toModelSettings(ModelSettingsSnapshot snapshot) {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        if (snapshot == null) {
            return settings;
        }
        settings.setProvider(snapshot.provider());
        settings.setDisplayName(snapshot.displayName());
        settings.setSupportsVision(snapshot.supportsVision());
        settings.setSupportsTemperature(snapshot.supportsTemperature());
        settings.setMaxInputTokens(snapshot.maxInputTokens());
        settings.setReasoning(toReasoningConfig(snapshot.reasoning()));
        return settings;
    }

    private ModelConfigService.ReasoningConfig toReasoningConfig(ReasoningConfigSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        ModelConfigService.ReasoningConfig config = new ModelConfigService.ReasoningConfig();
        config.setDefaultLevel(snapshot.defaultLevel());
        Map<String, ModelConfigService.ReasoningLevelConfig> levels = new LinkedHashMap<>();
        if (snapshot.levels() != null) {
            snapshot.levels().forEach((key, value) -> levels.put(
                    key,
                    new ModelConfigService.ReasoningLevelConfig(value.maxInputTokens())));
        }
        config.setLevels(levels);
        return config;
    }
}
