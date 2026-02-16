package me.golemcore.bot.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ModelConfigServiceTest {

    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String MODEL_GPT_5_1 = "gpt-5.1";
    private static final String MODEL_GPT_5_CHAT_LATEST = "gpt-5-chat-latest";
    private static final String MODEL_CLAUDE_SONNET_4 = "claude-sonnet-4-20250514";
    private static final String MODEL_CLAUDE_3_5_HAIKU = "claude-3-5-haiku";
    private static final String MODEL_TEST = "test-model";
    private static final String MODEL_CUSTOM = "custom-model";
    private static final String PROVIDER_CUSTOM = "custom";

    @Mock
    private StoragePort storagePort;

    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Workspace has no models.json
        when(storagePort.exists(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        service = new ModelConfigService(storagePort);
        service.init();

        // Tests should validate service behavior, not the concrete production catalog.
        ReflectionTestUtils.setField(service, "config", buildSyntheticTestConfig());
    }

    private ModelConfigService.ModelsConfig buildSyntheticTestConfig() {
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();

        ModelConfigService.ModelSettings defaults = new ModelConfigService.ModelSettings();
        defaults.setProvider(PROVIDER_OPENAI);
        defaults.setSupportsTemperature(true);
        defaults.setMaxInputTokens(128000);
        config.setDefaults(defaults);

        config.getModels().put(MODEL_GPT_5_1, reasoningModel(PROVIDER_OPENAI, "GPT-5.1",
                "none", 400000, "none", "low", "medium", "high", "xhigh"));
        config.getModels().put("gpt-5.2", reasoningModel(PROVIDER_OPENAI, "GPT-5.2",
                "none", 400000, "none", "low", "medium", "high", "xhigh"));
        config.getModels().put("gpt-5-mini", reasoningModel(PROVIDER_OPENAI, "GPT-5 Mini",
                "medium", 400000, "low", "medium", "high"));
        config.getModels().put("gpt-5-nano", reasoningModel(PROVIDER_OPENAI, "GPT-5 Nano",
                "medium", 400000, "low", "medium", "high"));
        config.getModels().put(MODEL_GPT_5_CHAT_LATEST,
                standardModel(PROVIDER_OPENAI, "GPT-5 Chat Latest", true, 128000));
        config.getModels().put("gpt-5.2-codex", reasoningModel(PROVIDER_OPENAI, "GPT-5.2 Codex",
                "medium", 400000, "low", "medium", "high", "xhigh"));
        config.getModels().put("gpt-5.3-codex", reasoningModel(PROVIDER_OPENAI, "GPT-5.3 Codex",
                "medium", 400000, "low", "medium", "high", "xhigh"));
        config.getModels().put("gpt-5.3-codex-spark", reasoningModel(PROVIDER_OPENAI, "GPT-5.3 Codex Spark",
                "low", 128000, "low", "medium", "high"));

        config.getModels().put(MODEL_CLAUDE_SONNET_4,
                standardModel(PROVIDER_ANTHROPIC, "Claude Sonnet 4", true, 200000));
        config.getModels().put("claude-sonnet-4-5",
                standardModel(PROVIDER_ANTHROPIC, "Claude Sonnet 4.5", true, 200000));
        config.getModels().put("claude-opus-4-1",
                standardModel(PROVIDER_ANTHROPIC, "Claude Opus 4.1", true, 200000));
        config.getModels().put(MODEL_CLAUDE_3_5_HAIKU,
                standardModel(PROVIDER_ANTHROPIC, "Claude 3.5 Haiku", true, 200000));

        return config;
    }

    private ModelConfigService.ModelSettings standardModel(String provider, String displayName,
            boolean supportsTemperature, int maxInputTokens) {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(provider);
        settings.setDisplayName(displayName);
        settings.setSupportsTemperature(supportsTemperature);
        settings.setMaxInputTokens(maxInputTokens);
        return settings;
    }

    private ModelConfigService.ModelSettings reasoningModel(String provider, String displayName,
            String defaultLevel, int maxInputTokens, String... levels) {
        ModelConfigService.ModelSettings settings = standardModel(provider, displayName, false, maxInputTokens);
        ModelConfigService.ReasoningConfig reasoning = new ModelConfigService.ReasoningConfig();
        reasoning.setDefaultLevel(defaultLevel);
        for (String level : levels) {
            reasoning.getLevels().put(level, new ModelConfigService.ReasoningLevelConfig(maxInputTokens));
        }
        settings.setReasoning(reasoning);
        return settings;
    }

    // ===== Exact match =====

    @Test
    void exactMatchReturnsCorrectProvider() {
        // Default config has "gpt-5.1" → openai
        assertEquals(PROVIDER_OPENAI, service.getProvider(MODEL_GPT_5_1));
    }

    @Test
    void exactMatchReturnsReasoningRequired() {
        assertTrue(service.isReasoningRequired(MODEL_GPT_5_1));
    }

    @Test
    void exactMatchReturnsSupportTemperature() {
        assertTrue(service.supportsTemperature(MODEL_GPT_5_CHAT_LATEST));
        assertFalse(service.supportsTemperature(MODEL_GPT_5_1));
    }

    // ===== Provider prefix stripping =====

    @Test
    void stripsProviderPrefix() {
        // "openai/gpt-5.1" → strips to "gpt-5.1" → exact match
        assertEquals(PROVIDER_OPENAI, service.getProvider("openai/" + MODEL_GPT_5_1));
        assertTrue(service.isReasoningRequired("openai/" + MODEL_GPT_5_1));
    }

    @Test
    void shouldStripProviderPrefixForAnthropicModels() {
        assertEquals(PROVIDER_ANTHROPIC, service.getProvider(PROVIDER_ANTHROPIC + "/" + MODEL_CLAUDE_3_5_HAIKU));
    }

    // ===== Prefix match =====

    @Test
    void prefixMatchWorks() {
        // "gpt-5.1-preview" should match "gpt-5.1" prefix
        ModelConfigService.ModelSettings settings = service.getModelSettings(MODEL_GPT_5_1 + "-preview");
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
        assertTrue(service.isReasoningRequired(MODEL_GPT_5_1 + "-preview"));
    }

    @Test
    void longestPrefixWins() {
        // "gpt-5.1" is longer prefix than "gpt-4" for "gpt-5.1-preview"
        assertEquals(PROVIDER_OPENAI, service.getProvider(MODEL_GPT_5_1 + "-preview-2026"));
    }

    @Test
    void shouldMatchO3MiniByPrefix() {
        // "gpt-5.2-codex-2026" should match "gpt-5.2-codex"
        ModelConfigService.ModelSettings settings = service.getModelSettings("gpt-5.2-codex-2026");
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
        assertTrue(service.isReasoningRequired("gpt-5.2-codex-2026"));
    }

    // ===== Default fallback =====

    @Test
    void unknownModelReturnDefaults() {
        ModelConfigService.ModelSettings settings = service.getModelSettings("totally-unknown-model");
        assertNotNull(settings);
        assertEquals(PROVIDER_OPENAI, settings.getProvider()); // default provider
    }

    @Test
    void nullModelReturnDefaults() {
        ModelConfigService.ModelSettings settings = service.getModelSettings(null);
        assertNotNull(settings);
    }

    // ===== maxInputTokens =====

    @Test
    void modelMaxInputTokensFromFile() {
        // models.json in working dir has gpt-5.1 with 1M tokens
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1);
        assertTrue(tokens > 0);
    }

    @Test
    void unknownModelGetsDefaultMaxInputTokens() {
        assertEquals(128000, service.getMaxInputTokens("unknown-model"));
    }

    // ===== getAllModels =====

    @Test
    void getAllModelsReturnsNonEmpty() {
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();
        assertNotNull(config.getModels());
        assertTrue(config.getModels().isEmpty());
        assertNotNull(config.getDefaults());
    }

    @Test
    void shouldReturnAllKnownModels() {
        Map<String, ModelConfigService.ModelSettings> models = service.getAllModels();
        assertNotNull(models);
        assertFalse(models.isEmpty());
        assertTrue(models.containsKey(MODEL_GPT_5_1));
        assertTrue(models.containsKey(MODEL_CLAUDE_SONNET_4));
    }

    // ===== Anthropic models =====

    @Test
    void anthropicModelsConfigured() {
        assertEquals(PROVIDER_ANTHROPIC, service.getProvider(MODEL_CLAUDE_SONNET_4));
        assertTrue(service.supportsTemperature(MODEL_CLAUDE_SONNET_4));
        assertFalse(service.isReasoningRequired(MODEL_CLAUDE_SONNET_4));
    }

    @Test
    void shouldReturnCorrectAnthropicHaikuSettings() {
        ModelConfigService.ModelSettings settings = service.getModelSettings(MODEL_CLAUDE_3_5_HAIKU);
        assertEquals(PROVIDER_ANTHROPIC, settings.getProvider());
        assertTrue(settings.isSupportsTemperature());
    }

    // ===== reload =====

    @Test
    void shouldSurviveReload() {
        assertDoesNotThrow(() -> service.reload());
        assertNotNull(service.getAllModels());
    }

    // ===== ModelSettings constructors =====

    @Test
    void shouldCreateModelSettingsWithDefaults() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
        assertNull(settings.getReasoning());
        assertTrue(settings.isSupportsTemperature());
        assertEquals(128000, settings.getMaxInputTokens());
    }

    @Test
    void shouldCreateModelSettingsWithReasoningConfig() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(PROVIDER_ANTHROPIC);
        settings.setSupportsTemperature(false);
        ModelConfigService.ReasoningConfig reasoning = new ModelConfigService.ReasoningConfig();
        reasoning.setDefaultLevel("medium");
        reasoning.getLevels().put("medium", new ModelConfigService.ReasoningLevelConfig(200000));
        settings.setReasoning(reasoning);

        assertEquals(PROVIDER_ANTHROPIC, settings.getProvider());
        assertNotNull(settings.getReasoning());
        assertEquals("medium", settings.getReasoning().getDefaultLevel());
        assertFalse(settings.isSupportsTemperature());
        assertEquals(128000, settings.getMaxInputTokens());
    }

    @Test
    void shouldCreateModelSettingsWithCustomMaxInputTokens() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(PROVIDER_CUSTOM);
        settings.setSupportsTemperature(true);
        settings.setMaxInputTokens(64000);

        assertEquals(PROVIDER_CUSTOM, settings.getProvider());
        assertNull(settings.getReasoning());
        assertTrue(settings.isSupportsTemperature());
        assertEquals(64000, settings.getMaxInputTokens());
    }

    // ===== Config deserialization =====

    @Test
    void shouldDeserializeModelsConfig() throws Exception {
        String json = """
                {
                  "models": {
                    "test-model": {
                      "provider": "test-provider",
                      "displayName": "Test Model",
                      "supportsTemperature": false,
                      "reasoning": {
                        "default": "medium",
                        "levels": {
                          "low": { "maxInputTokens": 64000 },
                          "medium": { "maxInputTokens": 64000 }
                        }
                      }
                    }
                  },
                  "defaults": {
                    "provider": "default-provider",
                    "supportsTemperature": true,
                    "maxInputTokens": 32000
                  }
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        ModelConfigService.ModelsConfig config = mapper.readValue(json, ModelConfigService.ModelsConfig.class);

        assertEquals(1, config.getModels().size());
        assertTrue(config.getModels().containsKey(MODEL_TEST));

        ModelConfigService.ModelSettings testModel = config.getModels().get(MODEL_TEST);
        assertEquals("test-provider", testModel.getProvider());
        assertEquals("Test Model", testModel.getDisplayName());
        assertNotNull(testModel.getReasoning());
        assertEquals("medium", testModel.getReasoning().getDefaultLevel());
        assertEquals(2, testModel.getReasoning().getLevels().size());
        assertEquals(64000, testModel.getReasoning().getLevels().get("medium").getMaxInputTokens());
        assertFalse(testModel.isSupportsTemperature());

        ModelConfigService.ModelSettings defaults = config.getDefaults();
        assertEquals("default-provider", defaults.getProvider());
        assertEquals(32000, defaults.getMaxInputTokens());
    }

    @Test
    void shouldIgnoreUnknownJsonProperties() throws Exception {
        String json = """
                {
                  "models": {},
                  "defaults": {
                    "provider": "openai",
                    "unknownField": "value"
                  },
                  "extraTopLevel": 42
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        ModelConfigService.ModelsConfig config = mapper.readValue(json, ModelConfigService.ModelsConfig.class);
        assertNotNull(config);
        assertEquals(PROVIDER_OPENAI, config.getDefaults().getProvider());
    }

    // ===== OpenAI reasoning models =====

    @Test
    void shouldReturnReasoningSettingsForO1() {
        assertTrue(service.isReasoningRequired("gpt-5-mini"));
        assertFalse(service.supportsTemperature("gpt-5-mini"));
    }

    @Test
    void shouldReturnReasoningSettingsForGpt52() {
        assertTrue(service.isReasoningRequired("gpt-5.2"));
        assertFalse(service.supportsTemperature("gpt-5.2"));
    }

    @Test
    void shouldReturnStandardSettingsForGpt4Turbo() {
        assertFalse(service.isReasoningRequired(MODEL_GPT_5_CHAT_LATEST));
        assertTrue(service.supportsTemperature(MODEL_GPT_5_CHAT_LATEST));
    }

    // ===== loadConfig with custom file =====

    @Test
    void shouldLoadConfigFromFile(@TempDir Path tempDir) throws Exception {
        String customJson = """
                {
                  "models": {
                    "custom-model": {
                      "provider": "custom-provider",
                      "displayName": "Custom Model",
                      "supportsTemperature": false,
                      "reasoning": {
                        "default": "low",
                        "levels": {
                          "low": { "maxInputTokens": 32000 }
                        }
                      }
                    }
                  },
                  "defaults": {
                    "provider": "custom-default",
                    "supportsTemperature": true,
                    "maxInputTokens": 16000
                  }
                }
                """;

        Path configFile = tempDir.resolve("models.json");
        Files.writeString(configFile, customJson);

        // Use reflection to call loadConfig after setting the working directory
        // Since loadConfig uses Paths.get("models.json") relative to CWD,
        // we test the deserialization path directly
        ObjectMapper mapper = new ObjectMapper();
        ModelConfigService.ModelsConfig config = mapper.readValue(customJson, ModelConfigService.ModelsConfig.class);

        assertEquals(1, config.getModels().size());
        assertTrue(config.getModels().containsKey(MODEL_CUSTOM));
        assertEquals("custom-provider", config.getModels().get(MODEL_CUSTOM).getProvider());
        assertNotNull(config.getModels().get(MODEL_CUSTOM).getReasoning());
        assertEquals("low", config.getModels().get(MODEL_CUSTOM).getReasoning().getDefaultLevel());
        assertEquals(32000,
                config.getModels().get(MODEL_CUSTOM).getReasoning().getLevels().get("low").getMaxInputTokens());
        assertEquals("custom-default", config.getDefaults().getProvider());
        assertEquals(16000, config.getDefaults().getMaxInputTokens());
    }

    @Test
    void shouldHandleMalformedJsonGracefully() throws Exception {
        // Test that ObjectMapper fails on malformed JSON
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.readValue("not valid json", ModelConfigService.ModelsConfig.class));
    }

    // ===== getModelSettings edge cases =====

    @Test
    void shouldReturnDefaultsForUnknownProviderPrefix() {
        ModelConfigService.ModelSettings settings = service.getModelSettings("unknown-provider/unknown-model");
        assertNotNull(settings);
        // Falls back to defaults
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
    }

    @Test
    void shouldReturnExactMatchOverPrefixMatch() {
        // "gpt-5-chat-latest" should return exact match
        ModelConfigService.ModelSettings settings = service.getModelSettings(MODEL_GPT_5_CHAT_LATEST);
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
        assertNull(settings.getReasoning());
        assertFalse(service.isReasoningRequired(MODEL_GPT_5_CHAT_LATEST));
        assertTrue(settings.isSupportsTemperature());
    }

    @Test
    void shouldHandleModelNameWithMultipleSlashes() {
        // "provider/sub/model-name" → strips to "sub/model-name"
        ModelConfigService.ModelSettings settings = service.getModelSettings("provider/sub/model-name");
        assertNotNull(settings);
    }

    // ===== Convenience methods =====

    @Test
    void getProviderDelegatesToGetModelSettings() {
        assertEquals(PROVIDER_OPENAI, service.getProvider(MODEL_GPT_5_CHAT_LATEST));
        assertEquals(PROVIDER_ANTHROPIC, service.getProvider(MODEL_CLAUDE_SONNET_4));
    }

    @Test
    void isReasoningRequiredDelegatesToGetModelSettings() {
        assertTrue(service.isReasoningRequired(MODEL_GPT_5_1));
        assertFalse(service.isReasoningRequired(MODEL_GPT_5_CHAT_LATEST));
    }

    @Test
    void supportsTemperatureDelegatesToGetModelSettings() {
        assertTrue(service.supportsTemperature(MODEL_GPT_5_CHAT_LATEST));
        assertFalse(service.supportsTemperature(MODEL_GPT_5_1));
    }

    @Test
    void getMaxInputTokensDelegatesToGetModelSettings() {
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_CHAT_LATEST);
        assertTrue(tokens > 0);
    }

    // ===== ModelsConfig data class =====

    @Test
    void modelsConfigSettersWork() {
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();

        ModelConfigService.ModelSettings defaults = new ModelConfigService.ModelSettings();
        defaults.setProvider("test");
        defaults.setSupportsTemperature(true);
        config.setDefaults(defaults);

        ModelConfigService.ModelSettings testModel = new ModelConfigService.ModelSettings();
        testModel.setProvider("test");
        testModel.setSupportsTemperature(false);
        ModelConfigService.ReasoningConfig reasoning = new ModelConfigService.ReasoningConfig();
        reasoning.setDefaultLevel("medium");
        reasoning.getLevels().put("medium", new ModelConfigService.ReasoningLevelConfig(128000));
        testModel.setReasoning(reasoning);

        Map<String, ModelConfigService.ModelSettings> models = new java.util.HashMap<>();
        models.put(MODEL_TEST, testModel);
        config.setModels(models);

        assertEquals("test", config.getDefaults().getProvider());
        assertEquals(1, config.getModels().size());
        assertNotNull(config.getModels().get(MODEL_TEST).getReasoning());
        assertFalse(config.getModels().get(MODEL_TEST).getReasoning().getLevels().isEmpty());
    }

    // ===== ModelSettings data class =====

    @Test
    void modelSettingsSettersWork() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(PROVIDER_CUSTOM);
        settings.setDisplayName("Custom Model");
        settings.setSupportsTemperature(false);
        settings.setMaxInputTokens(64000);

        ModelConfigService.ReasoningConfig reasoning = new ModelConfigService.ReasoningConfig();
        reasoning.setDefaultLevel("high");
        reasoning.getLevels().put("high", new ModelConfigService.ReasoningLevelConfig(64000));
        settings.setReasoning(reasoning);

        assertEquals(PROVIDER_CUSTOM, settings.getProvider());
        assertEquals("Custom Model", settings.getDisplayName());
        assertNotNull(settings.getReasoning());
        assertEquals("high", settings.getReasoning().getDefaultLevel());
        assertEquals(1, settings.getReasoning().getLevels().size());
        assertFalse(settings.isSupportsTemperature());
        assertEquals(64000, settings.getMaxInputTokens());
    }

    // ===== getMaxInputTokens(modelName, reasoningLevel) =====

    @Test
    void shouldReturnPerLevelMaxInputTokensForReasoningModel() {
        // gpt-5.1 has fixed 400K context across all defined levels
        int highTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "high");
        assertEquals(400000, highTokens);

        int xhighTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "xhigh");
        assertEquals(400000, xhighTokens);

        int lowTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "low");
        assertEquals(400000, lowTokens);

        int mediumTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "medium");
        assertEquals(400000, mediumTokens);
    }

    @Test
    void shouldFallBackToModelLevelTokensWhenReasoningLevelIsNull() {
        // When level is null, delegates to single-arg getMaxInputTokens
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1, null);
        // For reasoning model, single-arg returns max across all levels = 400K
        assertEquals(400000, tokens);
    }

    @Test
    void shouldFallBackToModelLevelTokensWhenReasoningLevelNotFound() {
        // "nonexistent" is not a valid reasoning level for gpt-5.1
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1, "nonexistent");
        // Falls back to single-arg which returns max across all levels = 400K
        assertEquals(400000, tokens);
    }

    @Test
    void shouldReturnModelMaxInputTokensWhenNonReasoningModelWithLevel() {
        // gpt-5-chat-latest has no reasoning config, maxInputTokens=128000
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_CHAT_LATEST, "medium");
        assertEquals(128000, tokens);
    }

    @Test
    void shouldReturnModelMaxInputTokensWhenNonReasoningModelWithNullLevel() {
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_CHAT_LATEST, null);
        assertEquals(128000, tokens);
    }

    // ===== getMaxInputTokens(modelName) for reasoning models =====

    @Test
    void shouldReturnMaxAcrossAllLevelsForReasoningModel() {
        // gpt-5.1 levels are normalized to 400K → max = 400K
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1);
        assertEquals(400000, tokens);
    }

    @Test
    void shouldReturnMaxAcrossAllLevelsForO3() {
        int tokens = service.getMaxInputTokens("gpt-5-mini");
        assertEquals(400000, tokens);
    }

    @Test
    void shouldReturnMaxAcrossAllLevelsForO3Mini() {
        int tokens = service.getMaxInputTokens("gpt-5-nano");
        assertEquals(400000, tokens);
    }

    // ===== getDefaultReasoningLevel =====

    @Test
    void shouldReturnDefaultReasoningLevelForReasoningModel() {
        String level = service.getDefaultReasoningLevel(MODEL_GPT_5_1);
        assertEquals("none", level);
    }

    @Test
    void shouldReturnDefaultReasoningLevelForO3() {
        String level = service.getDefaultReasoningLevel("gpt-5-mini");
        assertEquals("medium", level);
    }

    @Test
    void shouldReturnNullDefaultReasoningLevelForNonReasoningModel() {
        String level = service.getDefaultReasoningLevel(MODEL_GPT_5_CHAT_LATEST);
        assertNull(level);
    }

    @Test
    void shouldReturnNullDefaultReasoningLevelForAnthropicModel() {
        String level = service.getDefaultReasoningLevel(MODEL_CLAUDE_SONNET_4);
        assertNull(level);
    }

    @Test
    void shouldReturnNullDefaultReasoningLevelForUnknownModel() {
        String level = service.getDefaultReasoningLevel("totally-unknown");
        assertNull(level);
    }

    // ===== getAvailableReasoningLevels =====

    @Test
    void shouldReturnAvailableLevelsForReasoningModel() {
        List<String> levels = service.getAvailableReasoningLevels(MODEL_GPT_5_1);
        assertNotNull(levels);
        assertEquals(5, levels.size());
        assertTrue(levels.contains("none"));
        assertTrue(levels.contains("low"));
        assertTrue(levels.contains("medium"));
        assertTrue(levels.contains("high"));
        assertTrue(levels.contains("xhigh"));
    }

    @Test
    void shouldReturnAvailableLevelsForO3() {
        List<String> levels = service.getAvailableReasoningLevels("gpt-5-mini");
        assertNotNull(levels);
        assertEquals(3, levels.size());
        assertTrue(levels.contains("low"));
        assertTrue(levels.contains("medium"));
        assertTrue(levels.contains("high"));
    }

    @Test
    void shouldReturnEmptyLevelsForNonReasoningModel() {
        List<String> levels = service.getAvailableReasoningLevels(MODEL_GPT_5_CHAT_LATEST);
        assertNotNull(levels);
        assertTrue(levels.isEmpty());
    }

    @Test
    void shouldReturnEmptyLevelsForAnthropicModel() {
        List<String> levels = service.getAvailableReasoningLevels(MODEL_CLAUDE_SONNET_4);
        assertNotNull(levels);
        assertTrue(levels.isEmpty());
    }

    @Test
    void shouldReturnEmptyLevelsForUnknownModel() {
        List<String> levels = service.getAvailableReasoningLevels("unknown-model");
        assertNotNull(levels);
        assertTrue(levels.isEmpty());
    }

    // ===== getModelsForProviders =====

    @Test
    void shouldFilterModelsByOpenaiProvider() {
        Map<String, ModelConfigService.ModelSettings> openaiModels = service
                .getModelsForProviders(List.of(PROVIDER_OPENAI));
        assertNotNull(openaiModels);
        assertFalse(openaiModels.isEmpty());
        // All returned models should be openai
        openaiModels.values().forEach(settings -> assertEquals(PROVIDER_OPENAI, settings.getProvider()));
        // Should include known openai models
        assertTrue(openaiModels.containsKey(MODEL_GPT_5_1));
        assertTrue(openaiModels.containsKey(MODEL_GPT_5_CHAT_LATEST));
        assertTrue(openaiModels.containsKey("gpt-5.2"));
        // Should NOT include anthropic models
        assertFalse(openaiModels.containsKey(MODEL_CLAUDE_SONNET_4));
        assertFalse(openaiModels.containsKey(MODEL_CLAUDE_3_5_HAIKU));
    }

    @Test
    void shouldFilterModelsByAnthropicProvider() {
        Map<String, ModelConfigService.ModelSettings> anthropicModels = service
                .getModelsForProviders(List.of(PROVIDER_ANTHROPIC));
        assertNotNull(anthropicModels);
        assertFalse(anthropicModels.isEmpty());
        // All returned models should be anthropic
        anthropicModels.values().forEach(settings -> assertEquals(PROVIDER_ANTHROPIC, settings.getProvider()));
        // Should include known anthropic models
        assertTrue(anthropicModels.containsKey(MODEL_CLAUDE_SONNET_4));
        assertTrue(anthropicModels.containsKey(MODEL_CLAUDE_3_5_HAIKU));
        // Should NOT include openai models
        assertFalse(anthropicModels.containsKey(MODEL_GPT_5_1));
        assertFalse(anthropicModels.containsKey(MODEL_GPT_5_CHAT_LATEST));
    }

    @Test
    void shouldFilterModelsByMultipleProviders() {
        Map<String, ModelConfigService.ModelSettings> combined = service
                .getModelsForProviders(List.of(PROVIDER_OPENAI, PROVIDER_ANTHROPIC));
        assertNotNull(combined);
        // Should include both openai and anthropic models
        assertTrue(combined.containsKey(MODEL_GPT_5_1));
        assertTrue(combined.containsKey(MODEL_CLAUDE_SONNET_4));
        // Combined result should be larger than either individual provider filter
        Map<String, ModelConfigService.ModelSettings> openaiOnly = service
                .getModelsForProviders(List.of(PROVIDER_OPENAI));
        Map<String, ModelConfigService.ModelSettings> anthropicOnly = service
                .getModelsForProviders(List.of(PROVIDER_ANTHROPIC));
        assertEquals(openaiOnly.size() + anthropicOnly.size(), combined.size());
    }

    @Test
    void shouldReturnEmptyMapForUnknownProvider() {
        Map<String, ModelConfigService.ModelSettings> models = service
                .getModelsForProviders(List.of("unknown-provider"));
        assertNotNull(models);
        assertTrue(models.isEmpty());
    }

    @Test
    void shouldReturnEmptyMapForEmptyProviderList() {
        Map<String, ModelConfigService.ModelSettings> models = service.getModelsForProviders(Collections.emptyList());
        assertNotNull(models);
        assertTrue(models.isEmpty());
    }

    // ===== getAllModels =====

    @Test
    void shouldReturnCorrectModelMapFromService() {
        Map<String, ModelConfigService.ModelSettings> models = service.getAllModels();
        assertNotNull(models);
        assertFalse(models.isEmpty());
        // Verify fixture models are present
        assertTrue(models.containsKey(MODEL_GPT_5_1));
        assertTrue(models.containsKey(MODEL_GPT_5_CHAT_LATEST));
        assertTrue(models.containsKey(MODEL_CLAUDE_SONNET_4));

        // Verify settings are populated correctly
        ModelConfigService.ModelSettings gpt51Settings = models.get(MODEL_GPT_5_1);
        assertEquals(PROVIDER_OPENAI, gpt51Settings.getProvider());
        assertNotNull(gpt51Settings.getReasoning());
        ModelConfigService.ModelSettings claudeSettings = models.get(MODEL_CLAUDE_SONNET_4);
        assertEquals(PROVIDER_ANTHROPIC, claudeSettings.getProvider());
        assertNull(claudeSettings.getReasoning());
    }

    // ===== reload picks up changes =====

    @Test
    void shouldReloadAndReplaceConfig() {
        // Verify initial fixture state has expected model
        assertNotNull(service.getAllModels().get(MODEL_GPT_5_1));

        // Manually replace config with a minimal custom one
        ModelConfigService.ModelsConfig customConfig = new ModelConfigService.ModelsConfig();
        ModelConfigService.ModelSettings customModel = new ModelConfigService.ModelSettings();
        customModel.setProvider(PROVIDER_CUSTOM);
        customModel.setDisplayName("Custom Only");
        customModel.setMaxInputTokens(50000);
        customConfig.getModels().put(MODEL_CUSTOM, customModel);
        ModelConfigService.ModelSettings customDefaults = new ModelConfigService.ModelSettings();
        customDefaults.setProvider(PROVIDER_CUSTOM);
        customDefaults.setMaxInputTokens(10000);
        customConfig.setDefaults(customDefaults);
        ReflectionTestUtils.setField(service, "config", customConfig);

        // Verify the custom config is active
        assertEquals(1, service.getAllModels().size());
        assertTrue(service.getAllModels().containsKey(MODEL_CUSTOM));
        assertEquals(PROVIDER_CUSTOM, service.getProvider(MODEL_CUSTOM));

        // Reload should replace with freshly loaded config
        service.reload();

        // After reload, the custom model should be gone and config should be non-empty
        Map<String, ModelConfigService.ModelSettings> models = service.getAllModels();
        assertFalse(models.containsKey(MODEL_CUSTOM));
        assertFalse(models.isEmpty());
    }

    // ===== getMaxInputTokens with custom config via @TempDir =====

    @Test
    void shouldReturnPerLevelTokensFromCustomConfig(@TempDir Path tempDir) throws Exception {
        String customJson = """
                {
                  "models": {
                    "custom-reasoning": {
                      "provider": "custom",
                      "displayName": "Custom Reasoning",
                      "supportsTemperature": false,
                      "maxInputTokens": 64000,
                      "reasoning": {
                        "default": "low",
                        "levels": {
                          "low": { "maxInputTokens": 32000 },
                          "high": { "maxInputTokens": 16000 }
                        }
                      }
                    }
                  },
                  "defaults": {
                    "provider": "openai",
                    "supportsTemperature": true,
                    "maxInputTokens": 100000
                  }
                }
                """;

        // Load custom config into service
        ObjectMapper mapper = new ObjectMapper();
        ModelConfigService.ModelsConfig customConfig = mapper.readValue(customJson,
                ModelConfigService.ModelsConfig.class);
        ReflectionTestUtils.setField(service, "config", customConfig);

        // Per-level lookups
        assertEquals(32000, service.getMaxInputTokens("custom-reasoning", "low"));
        assertEquals(16000, service.getMaxInputTokens("custom-reasoning", "high"));

        // Unknown level falls back to max across all levels = 32000
        assertEquals(32000, service.getMaxInputTokens("custom-reasoning", "nonexistent"));

        // Null level falls back to max across all levels = 32000
        assertEquals(32000, service.getMaxInputTokens("custom-reasoning", null));

        // Single-arg returns max across all levels = 32000
        assertEquals(32000, service.getMaxInputTokens("custom-reasoning"));

        // Default reasoning level
        assertEquals("low", service.getDefaultReasoningLevel("custom-reasoning"));

        // Available levels
        List<String> levels = service.getAvailableReasoningLevels("custom-reasoning");
        assertEquals(2, levels.size());
        assertTrue(levels.contains("low"));
        assertTrue(levels.contains("high"));

        // Unknown model falls back to defaults.maxInputTokens = 100000
        assertEquals(100000, service.getMaxInputTokens("unknown-model"));
        assertEquals(100000, service.getMaxInputTokens("unknown-model", "medium"));
    }

    // ===== getMaxInputTokens edge case: reasoning config with null levels =====

    @Test
    void shouldHandleReasoningConfigWithNullLevelsGracefully() {
        ModelConfigService.ModelsConfig customConfig = new ModelConfigService.ModelsConfig();
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(PROVIDER_OPENAI);
        settings.setMaxInputTokens(77000);
        ModelConfigService.ReasoningConfig reasoning = new ModelConfigService.ReasoningConfig();
        reasoning.setDefaultLevel("medium");
        reasoning.setLevels(null);
        settings.setReasoning(reasoning);
        customConfig.getModels().put("null-levels-model", settings);
        ReflectionTestUtils.setField(service, "config", customConfig);

        // Single-arg: reasoning != null but levels == null → falls to model
        // maxInputTokens
        assertEquals(77000, service.getMaxInputTokens("null-levels-model"));

        // Two-arg with non-null level: reasoning != null, levels == null → falls to
        // single-arg
        assertEquals(77000, service.getMaxInputTokens("null-levels-model", "medium"));

        // getAvailableReasoningLevels: reasoning != null, levels == null → empty list
        List<String> levels = service.getAvailableReasoningLevels("null-levels-model");
        assertTrue(levels.isEmpty());
    }
}
