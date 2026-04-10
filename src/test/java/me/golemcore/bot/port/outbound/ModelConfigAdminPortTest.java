package me.golemcore.bot.port.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelConfigAdminPortTest {

    @Test
    void shouldDefaultNullMapsToEmptySnapshots() {
        ModelConfigAdminPort.ModelsConfigSnapshot config = new ModelConfigAdminPort.ModelsConfigSnapshot(null, null);
        ModelConfigAdminPort.ReasoningConfigSnapshot reasoning = new ModelConfigAdminPort.ReasoningConfigSnapshot(
                "balanced", null);

        assertTrue(config.models().isEmpty());
        assertTrue(reasoning.levels().isEmpty());
    }

    @Test
    void shouldCopyMutableMapsDefensively() {
        Map<String, ModelConfigAdminPort.ModelSettingsSnapshot> models = new LinkedHashMap<>();
        models.put("gpt-5",
                new ModelConfigAdminPort.ModelSettingsSnapshot("openai", "GPT-5", true, true, 128000, null));
        ModelConfigAdminPort.ModelsConfigSnapshot snapshot = new ModelConfigAdminPort.ModelsConfigSnapshot(models,
                null);
        models.clear();

        Map<String, ModelConfigAdminPort.ReasoningLevelSnapshot> levels = new LinkedHashMap<>();
        levels.put("high", new ModelConfigAdminPort.ReasoningLevelSnapshot(200000));
        ModelConfigAdminPort.ReasoningConfigSnapshot reasoning = new ModelConfigAdminPort.ReasoningConfigSnapshot(
                "high", levels);
        levels.clear();

        assertEquals(1, snapshot.models().size());
        assertEquals(1, reasoning.levels().size());
    }
}
