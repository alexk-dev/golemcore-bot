package me.golemcore.bot.adapter.outbound.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.ModelConfigAdminPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelConfigAdminAdapterTest {

    private ModelConfigService modelConfigService;
    private ModelConfigAdminAdapter adapter;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(ModelConfigService.class);
        adapter = new ModelConfigAdminAdapter(modelConfigService);
    }

    @Test
    void shouldMapConfigSnapshotWithReasoningLevels() {
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();
        config.setModels(new LinkedHashMap<>(Map.of("gpt-5", modelSettings("openai", "GPT-5", true, false, 200000))));
        config.setDefaults(modelSettings("openai", "Default GPT", false, true, 100000));

        when(modelConfigService.getConfig()).thenReturn(config);

        ModelConfigAdminPort.ModelsConfigSnapshot snapshot = adapter.getConfig();

        assertEquals("GPT-5", snapshot.models().get("gpt-5").displayName());
        assertTrue(snapshot.models().get("gpt-5").reasoning().levels().containsKey("high"));
        assertEquals("balanced", snapshot.defaults().reasoning().defaultLevel());
    }

    @Test
    void shouldReplaceConfigUsingSnapshotMapping() {
        AtomicReference<ModelConfigService.ModelsConfig> savedConfig = new AtomicReference<>();
        doAnswer(invocation -> {
            savedConfig.set(invocation.getArgument(0));
            return null;
        })
                .when(modelConfigService)
                .replaceConfig(any(ModelConfigService.ModelsConfig.class));
        when(modelConfigService.getConfig()).thenAnswer(invocation -> savedConfig.get());

        ModelConfigAdminPort.ModelsConfigSnapshot snapshot = new ModelConfigAdminPort.ModelsConfigSnapshot(
                Map.of("gpt-5", new ModelConfigAdminPort.ModelSettingsSnapshot(
                        "openai",
                        "GPT-5",
                        true,
                        false,
                        200000,
                        new ModelConfigAdminPort.ReasoningConfigSnapshot(
                                "high",
                                Map.of("high", new ModelConfigAdminPort.ReasoningLevelSnapshot(200000))))),
                null);

        ModelConfigAdminPort.ModelsConfigSnapshot replaced = adapter.replaceConfig(snapshot);

        assertNotNull(savedConfig.get());
        assertEquals("openai", savedConfig.get().getModels().get("gpt-5").getProvider());
        assertEquals("high",
                savedConfig.get().getModels().get("gpt-5").getReasoning().getDefaultLevel());
        assertEquals("GPT-5", replaced.models().get("gpt-5").displayName());
        assertNotNull(replaced.defaults());
    }

    @Test
    void shouldDelegateSaveDeleteAndReload() {
        ModelConfigAdminPort.ModelSettingsSnapshot settings = new ModelConfigAdminPort.ModelSettingsSnapshot(
                "anthropic",
                "Claude Opus",
                true,
                true,
                180000,
                null);
        when(modelConfigService.deleteModel("claude-opus")).thenReturn(true);

        adapter.saveModel("claude-opus", "claude-legacy", settings);
        boolean deleted = adapter.deleteModel("claude-opus");
        adapter.reload();

        verify(modelConfigService)
                .saveModel(eq("claude-opus"), eq("claude-legacy"), any(ModelConfigService.ModelSettings.class));
        verify(modelConfigService).reload();
        assertTrue(deleted);
    }

    private ModelConfigService.ModelSettings modelSettings(
            String provider, String displayName, boolean supportsVision, boolean supportsTemperature, int maxTokens) {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(provider);
        settings.setDisplayName(displayName);
        settings.setSupportsVision(supportsVision);
        settings.setSupportsTemperature(supportsTemperature);
        settings.setMaxInputTokens(maxTokens);

        ModelConfigService.ReasoningConfig reasoning = new ModelConfigService.ReasoningConfig();
        reasoning.setDefaultLevel("balanced");
        reasoning.setLevels(Map.of(
                "high", new ModelConfigService.ReasoningLevelConfig(maxTokens),
                "low", new ModelConfigService.ReasoningLevelConfig(maxTokens / 2)));
        settings.setReasoning(reasoning);
        return settings;
    }
}
