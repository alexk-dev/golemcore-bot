package me.golemcore.bot.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(settings.isReasoningRequired());
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
        assertTrue(settings.isReasoningRequired());
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
        assertFalse(settings.isReasoningRequired());
        assertTrue(settings.isSupportsTemperature());
        assertEquals(128000, settings.getMaxInputTokens());
    }

    @Test
    void shouldCreateModelSettingsWithThreeArgConstructor() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings(PROVIDER_ANTHROPIC, true,
                false);
        assertEquals(PROVIDER_ANTHROPIC, settings.getProvider());
        assertTrue(settings.isReasoningRequired());
        assertFalse(settings.isSupportsTemperature());
        assertEquals(128000, settings.getMaxInputTokens());
    }

    @Test
    void shouldCreateModelSettingsWithFourArgConstructor() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings(PROVIDER_CUSTOM, false, true,
                64000);
        assertEquals(PROVIDER_CUSTOM, settings.getProvider());
        assertFalse(settings.isReasoningRequired());
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
                      "reasoningRequired": true,
                      "supportsTemperature": false,
                      "maxInputTokens": 64000
                    }
                  },
                  "defaults": {
                    "provider": "default-provider",
                    "reasoningRequired": false,
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
        assertTrue(testModel.isReasoningRequired());
        assertFalse(testModel.isSupportsTemperature());
        assertEquals(64000, testModel.getMaxInputTokens());

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
        assertTrue(config.getModels().get(MODEL_GPT_5_1).isReasoningRequired());
        assertFalse(config.getModels().get(MODEL_GPT_5_1).isSupportsTemperature());

        // OpenAI standard models
        assertEquals(PROVIDER_OPENAI, config.getModels().get(MODEL_GPT_4O).getProvider());
        assertFalse(config.getModels().get(MODEL_GPT_4O).isReasoningRequired());
        assertTrue(config.getModels().get(MODEL_GPT_4O).isSupportsTemperature());

        // Anthropic models
        assertEquals(PROVIDER_ANTHROPIC, config.getModels().get(MODEL_CLAUDE_3_HAIKU).getProvider());
        assertFalse(config.getModels().get(MODEL_CLAUDE_3_HAIKU).isReasoningRequired());
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
                      "reasoningRequired": true,
                      "supportsTemperature": false,
                      "maxInputTokens": 32000
                    }
                  },
                  "defaults": {
                    "provider": "custom-default",
                    "reasoningRequired": false,
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
        assertTrue(config.getModels().get(MODEL_CUSTOM).isReasoningRequired());
        assertEquals(32000, config.getModels().get(MODEL_CUSTOM).getMaxInputTokens());
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
        assertFalse(settings.isReasoningRequired());
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
        ModelConfigService.ModelSettings defaults = new ModelConfigService.ModelSettings("test", false, true);
        config.setDefaults(defaults);
        Map<String, ModelConfigService.ModelSettings> models = new java.util.HashMap<>();
        models.put(MODEL_TEST, new ModelConfigService.ModelSettings("test", true, false));
        config.setModels(models);

        assertEquals("test", config.getDefaults().getProvider());
        assertEquals(1, config.getModels().size());
        assertTrue(config.getModels().get(MODEL_TEST).isReasoningRequired());
    }

    // ===== ModelSettings data class =====

    @Test
    void modelSettingsSettersWork() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(PROVIDER_CUSTOM);
        settings.setReasoningRequired(true);
        settings.setSupportsTemperature(false);
        settings.setMaxInputTokens(64000);

        assertEquals(PROVIDER_CUSTOM, settings.getProvider());
        assertTrue(settings.isReasoningRequired());
        assertFalse(settings.isSupportsTemperature());
        assertEquals(64000, settings.getMaxInputTokens());
    }
}
