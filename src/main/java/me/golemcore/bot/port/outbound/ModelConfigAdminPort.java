package me.golemcore.bot.port.outbound;

import java.util.LinkedHashMap;
import java.util.Map;

public interface ModelConfigAdminPort {

    ModelsConfigSnapshot getConfig();

    ModelsConfigSnapshot replaceConfig(ModelsConfigSnapshot newConfig);

    void saveModel(String id, String previousId, ModelSettingsSnapshot settings);

    boolean deleteModel(String id);

    void reload();

    record ModelsConfigSnapshot(Map<String, ModelSettingsSnapshot> models, ModelSettingsSnapshot defaults) {
        public ModelsConfigSnapshot {
            models = models == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(models));
        }
    }

    record ModelSettingsSnapshot(
            String provider,
            String displayName,
            boolean supportsVision,
            boolean supportsTemperature,
            int maxInputTokens,
            ReasoningConfigSnapshot reasoning) {
    }

    record ReasoningConfigSnapshot(String defaultLevel, Map<String, ReasoningLevelSnapshot> levels) {
        public ReasoningConfigSnapshot {
            levels = levels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(levels));
        }
    }

    record ReasoningLevelSnapshot(int maxInputTokens) {
    }
}
