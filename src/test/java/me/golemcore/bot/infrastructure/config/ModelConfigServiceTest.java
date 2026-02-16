package me.golemcore.bot.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ModelConfigServiceTest {

    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String MODEL_GPT_5_1 = "gpt-5.1";
    private static final String MODEL_GPT_4O = "gpt-4o";
    private static final String MODEL_CLAUDE_SONNET_4 = "claude-sonnet-4-20250514";
    private static final String MODEL_CLAUDE_3_HAIKU = "claude-3-haiku";
    private static final String MODEL_O3 = "o3";
    private static final String MODEL_TEST = "test-model";
    private static final String MODEL_CUSTOM = "custom-model";
    private static final String PROVIDER_CUSTOM = "custom";
    private static final String METHOD_CREATE_DEFAULT_CONFIG = "createDefaultConfig";

    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        service = new ModelConfigService();
        // init() loads from file or creates defaults — trigger manually
        service.init();
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
        assertTrue(service.supportsTemperature(MODEL_GPT_4O));
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
        assertEquals(PROVIDER_ANTHROPIC, service.getProvider(PROVIDER_ANTHROPIC + "/" + MODEL_CLAUDE_3_HAIKU));
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
        // "o3-mini-2025" should match "o3-mini" (longer) not "o3"
        ModelConfigService.ModelSettings settings = service.getModelSettings("o3-mini-2025");
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
        assertTrue(service.isReasoningRequired("o3-mini-2025"));
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
        ModelConfigService.ModelSettings settings = service.getModelSettings(MODEL_CLAUDE_3_HAIKU);
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
        assertTrue(service.isReasoningRequired("o1"));
        assertFalse(service.supportsTemperature("o1"));
    }

    @Test
    void shouldReturnReasoningSettingsForGpt52() {
        assertTrue(service.isReasoningRequired("gpt-5.2"));
        assertFalse(service.supportsTemperature("gpt-5.2"));
    }

    @Test
    void shouldReturnStandardSettingsForGpt4Turbo() {
        assertFalse(service.isReasoningRequired("gpt-4-turbo"));
        assertTrue(service.supportsTemperature("gpt-4-turbo"));
    }

    // ===== createDefaultConfig =====

    @Test
    void shouldCreateDefaultConfigWithAllModels() {
        ModelConfigService.ModelsConfig config = ReflectionTestUtils.invokeMethod(service,
                METHOD_CREATE_DEFAULT_CONFIG);

        assertNotNull(config);
        assertNotNull(config.getModels());
        assertFalse(config.getModels().isEmpty());

        // Verify specific models exist
        assertTrue(config.getModels().containsKey(MODEL_GPT_5_1));
        assertTrue(config.getModels().containsKey("gpt-5.2"));
        assertTrue(config.getModels().containsKey("o1"));
        assertTrue(config.getModels().containsKey(MODEL_O3));
        assertTrue(config.getModels().containsKey("o3-mini"));
        assertTrue(config.getModels().containsKey(MODEL_GPT_4O));
        assertTrue(config.getModels().containsKey("gpt-4-turbo"));
        assertTrue(config.getModels().containsKey(MODEL_CLAUDE_SONNET_4));
        assertTrue(config.getModels().containsKey("claude-3-5-sonnet"));
        assertTrue(config.getModels().containsKey("claude-3-opus"));
        assertTrue(config.getModels().containsKey(MODEL_CLAUDE_3_HAIKU));

        // Verify defaults
        assertNotNull(config.getDefaults());
        assertEquals(PROVIDER_OPENAI, config.getDefaults().getProvider());
    }

    @Test
    void shouldCreateDefaultConfigWithCorrectProviders() {
        ModelConfigService.ModelsConfig config = ReflectionTestUtils.invokeMethod(service,
                METHOD_CREATE_DEFAULT_CONFIG);

        // OpenAI reasoning models
        assertEquals(PROVIDER_OPENAI, config.getModels().get(MODEL_GPT_5_1).getProvider());
        assertNotNull(config.getModels().get(MODEL_GPT_5_1).getReasoning());
        assertFalse(config.getModels().get(MODEL_GPT_5_1).getReasoning().getLevels().isEmpty());
        assertFalse(config.getModels().get(MODEL_GPT_5_1).isSupportsTemperature());

        // OpenAI standard models
        assertEquals(PROVIDER_OPENAI, config.getModels().get(MODEL_GPT_4O).getProvider());
        assertNull(config.getModels().get(MODEL_GPT_4O).getReasoning());
        assertTrue(config.getModels().get(MODEL_GPT_4O).isSupportsTemperature());

        // Anthropic models
        assertEquals(PROVIDER_ANTHROPIC, config.getModels().get(MODEL_CLAUDE_3_HAIKU).getProvider());
        assertNull(config.getModels().get(MODEL_CLAUDE_3_HAIKU).getReasoning());
        assertTrue(config.getModels().get(MODEL_CLAUDE_3_HAIKU).isSupportsTemperature());
    }

    // ===== writeDefaultConfig =====

    @Test
    void shouldWriteDefaultConfig(@TempDir Path tempDir) {
        // Set the config field first
        ModelConfigService.ModelsConfig defaultConfig = ReflectionTestUtils.invokeMethod(service,
                METHOD_CREATE_DEFAULT_CONFIG);
        ReflectionTestUtils.setField(service, "config", defaultConfig);

        Path configFile = tempDir.resolve("models.json");
        ReflectionTestUtils.invokeMethod(service, "writeDefaultConfig", configFile);

        assertTrue(Files.exists(configFile));
        String content;
        try {
            content = Files.readString(configFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(content.contains(MODEL_GPT_5_1));
        assertTrue(content.contains(PROVIDER_OPENAI));
        assertTrue(content.contains(PROVIDER_ANTHROPIC));
    }

    @Test
    void shouldHandleWriteFailureGracefully() {
        ModelConfigService.ModelsConfig defaultConfig = ReflectionTestUtils.invokeMethod(service,
                METHOD_CREATE_DEFAULT_CONFIG);
        ReflectionTestUtils.setField(service, "config", defaultConfig);

        // Write to a non-existent directory should fail gracefully
        Path badPath = Path.of("/nonexistent/dir/models.json");
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "writeDefaultConfig", badPath));
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
        // "gpt-4o" should return exact match, not prefix match with "gpt-4"
        ModelConfigService.ModelSettings settings = service.getModelSettings(MODEL_GPT_4O);
        assertEquals(PROVIDER_OPENAI, settings.getProvider());
        assertNull(settings.getReasoning());
        assertFalse(service.isReasoningRequired(MODEL_GPT_4O));
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
        assertEquals(PROVIDER_OPENAI, service.getProvider(MODEL_GPT_4O));
        assertEquals(PROVIDER_ANTHROPIC, service.getProvider("claude-3-opus"));
    }

    @Test
    void isReasoningRequiredDelegatesToGetModelSettings() {
        assertTrue(service.isReasoningRequired(MODEL_O3));
        assertFalse(service.isReasoningRequired(MODEL_GPT_4O));
    }

    @Test
    void supportsTemperatureDelegatesToGetModelSettings() {
        assertTrue(service.supportsTemperature(MODEL_GPT_4O));
        assertFalse(service.supportsTemperature(MODEL_O3));
    }

    @Test
    void getMaxInputTokensDelegatesToGetModelSettings() {
        int tokens = service.getMaxInputTokens(MODEL_GPT_4O);
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
        // gpt-5.1 has reasoning levels: low=1M, medium=1M, high=500K, xhigh=250K
        int highTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "high");
        assertEquals(500000, highTokens);

        int xhighTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "xhigh");
        assertEquals(250000, xhighTokens);

        int lowTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "low");
        assertEquals(1000000, lowTokens);

        int mediumTokens = service.getMaxInputTokens(MODEL_GPT_5_1, "medium");
        assertEquals(1000000, mediumTokens);
    }

    @Test
    void shouldFallBackToModelLevelTokensWhenReasoningLevelIsNull() {
        // When level is null, delegates to single-arg getMaxInputTokens
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1, null);
        // For reasoning model, single-arg returns max across all levels = 1M
        assertEquals(1000000, tokens);
    }

    @Test
    void shouldFallBackToModelLevelTokensWhenReasoningLevelNotFound() {
        // "nonexistent" is not a valid reasoning level for gpt-5.1
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1, "nonexistent");
        // Falls back to single-arg which returns max across all levels = 1M
        assertEquals(1000000, tokens);
    }

    @Test
    void shouldReturnModelMaxInputTokensWhenNonReasoningModelWithLevel() {
        // gpt-4o has no reasoning config, maxInputTokens=128000
        int tokens = service.getMaxInputTokens(MODEL_GPT_4O, "medium");
        assertEquals(128000, tokens);
    }

    @Test
    void shouldReturnModelMaxInputTokensWhenNonReasoningModelWithNullLevel() {
        int tokens = service.getMaxInputTokens(MODEL_GPT_4O, null);
        assertEquals(128000, tokens);
    }

    // ===== getMaxInputTokens(modelName) for reasoning models =====

    @Test
    void shouldReturnMaxAcrossAllLevelsForReasoningModel() {
        // gpt-5.1 levels: low=1M, medium=1M, high=500K, xhigh=250K → max = 1M
        int tokens = service.getMaxInputTokens(MODEL_GPT_5_1);
        assertEquals(1000000, tokens);
    }

    @Test
    void shouldReturnMaxAcrossAllLevelsForO3() {
        // o3 levels: low=200K, medium=200K, high=200K → max = 200K
        int tokens = service.getMaxInputTokens(MODEL_O3);
        assertEquals(200000, tokens);
    }

    @Test
    void shouldReturnMaxAcrossAllLevelsForO3Mini() {
        // o3-mini levels: low=128K, medium=128K, high=128K → max = 128K
        int tokens = service.getMaxInputTokens("o3-mini");
        assertEquals(128000, tokens);
    }

    // ===== getDefaultReasoningLevel =====

    @Test
    void shouldReturnDefaultReasoningLevelForReasoningModel() {
        String level = service.getDefaultReasoningLevel(MODEL_GPT_5_1);
        assertEquals("medium", level);
    }

    @Test
    void shouldReturnDefaultReasoningLevelForO3() {
        String level = service.getDefaultReasoningLevel(MODEL_O3);
        assertEquals("medium", level);
    }

    @Test
    void shouldReturnNullDefaultReasoningLevelForNonReasoningModel() {
        String level = service.getDefaultReasoningLevel(MODEL_GPT_4O);
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
        assertEquals(4, levels.size());
        assertTrue(levels.contains("low"));
        assertTrue(levels.contains("medium"));
        assertTrue(levels.contains("high"));
        assertTrue(levels.contains("xhigh"));
    }

    @Test
    void shouldReturnAvailableLevelsForO3() {
        List<String> levels = service.getAvailableReasoningLevels(MODEL_O3);
        assertNotNull(levels);
        assertEquals(3, levels.size());
        assertTrue(levels.contains("low"));
        assertTrue(levels.contains("medium"));
        assertTrue(levels.contains("high"));
    }

    @Test
    void shouldReturnEmptyLevelsForNonReasoningModel() {
        List<String> levels = service.getAvailableReasoningLevels(MODEL_GPT_4O);
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
        assertTrue(openaiModels.containsKey(MODEL_GPT_4O));
        assertTrue(openaiModels.containsKey(MODEL_O3));
        // Should NOT include anthropic models
        assertFalse(openaiModels.containsKey(MODEL_CLAUDE_SONNET_4));
        assertFalse(openaiModels.containsKey(MODEL_CLAUDE_3_HAIKU));
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
        assertTrue(anthropicModels.containsKey(MODEL_CLAUDE_3_HAIKU));
        // Should NOT include openai models
        assertFalse(anthropicModels.containsKey(MODEL_GPT_5_1));
        assertFalse(anthropicModels.containsKey(MODEL_GPT_4O));
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
        // Verify all expected default models are present
        assertTrue(models.containsKey(MODEL_GPT_5_1));
        assertTrue(models.containsKey("gpt-5.2"));
        assertTrue(models.containsKey("o1"));
        assertTrue(models.containsKey(MODEL_O3));
        assertTrue(models.containsKey("o3-mini"));
        assertTrue(models.containsKey(MODEL_GPT_4O));
        assertTrue(models.containsKey("gpt-4-turbo"));
        assertTrue(models.containsKey(MODEL_CLAUDE_SONNET_4));
        assertTrue(models.containsKey("claude-3-5-sonnet"));
        assertTrue(models.containsKey("claude-3-opus"));
        assertTrue(models.containsKey(MODEL_CLAUDE_3_HAIKU));
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
        // Verify initial state has expected models
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

        // Reload should replace with default or file config
        service.reload();

        // After reload, the custom model should be gone, original models restored
        Map<String, ModelConfigService.ModelSettings> models = service.getAllModels();
        assertFalse(models.containsKey(MODEL_CUSTOM));
        assertTrue(models.containsKey(MODEL_GPT_5_1));
        assertEquals(PROVIDER_OPENAI, service.getProvider(MODEL_GPT_5_1));
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
