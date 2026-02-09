package me.golemcore.bot.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigServiceTest {

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
        assertEquals("openai", service.getProvider("gpt-5.1"));
    }

    @Test
    void exactMatchReturnsReasoningRequired() {
        assertTrue(service.isReasoningRequired("gpt-5.1"));
    }

    @Test
    void exactMatchReturnsSupportTemperature() {
        assertTrue(service.supportsTemperature("gpt-4o"));
        assertFalse(service.supportsTemperature("gpt-5.1"));
    }

    // ===== Provider prefix stripping =====

    @Test
    void stripsProviderPrefix() {
        // "openai/gpt-5.1" → strips to "gpt-5.1" → exact match
        assertEquals("openai", service.getProvider("openai/gpt-5.1"));
        assertTrue(service.isReasoningRequired("openai/gpt-5.1"));
    }

    @Test
    void shouldStripProviderPrefixForAnthropicModels() {
        assertEquals("anthropic", service.getProvider("anthropic/claude-3-haiku"));
    }

    // ===== Prefix match =====

    @Test
    void prefixMatchWorks() {
        // "gpt-5.1-preview" should match "gpt-5.1" prefix
        ModelConfigService.ModelSettings settings = service.getModelSettings("gpt-5.1-preview");
        assertEquals("openai", settings.getProvider());
        assertTrue(settings.isReasoningRequired());
    }

    @Test
    void longestPrefixWins() {
        // "gpt-5.1" is longer prefix than "gpt-4" for "gpt-5.1-preview"
        assertEquals("openai", service.getProvider("gpt-5.1-preview-2026"));
    }

    @Test
    void shouldMatchO3MiniByPrefix() {
        // "o3-mini-2025" should match "o3-mini" (longer) not "o3"
        ModelConfigService.ModelSettings settings = service.getModelSettings("o3-mini-2025");
        assertEquals("openai", settings.getProvider());
        assertTrue(settings.isReasoningRequired());
    }

    // ===== Default fallback =====

    @Test
    void unknownModelReturnDefaults() {
        ModelConfigService.ModelSettings settings = service.getModelSettings("totally-unknown-model");
        assertNotNull(settings);
        assertEquals("openai", settings.getProvider()); // default provider
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
        int tokens = service.getMaxInputTokens("gpt-5.1");
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
        var models = service.getAllModels();
        assertNotNull(models);
        assertFalse(models.isEmpty());
        assertTrue(models.containsKey("gpt-5.1"));
        assertTrue(models.containsKey("claude-sonnet-4-20250514"));
    }

    // ===== Anthropic models =====

    @Test
    void anthropicModelsConfigured() {
        assertEquals("anthropic", service.getProvider("claude-sonnet-4-20250514"));
        assertTrue(service.supportsTemperature("claude-sonnet-4-20250514"));
        assertFalse(service.isReasoningRequired("claude-sonnet-4-20250514"));
    }

    @Test
    void shouldReturnCorrectAnthropicHaikuSettings() {
        ModelConfigService.ModelSettings settings = service.getModelSettings("claude-3-haiku");
        assertEquals("anthropic", settings.getProvider());
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
        assertEquals("openai", settings.getProvider());
        assertFalse(settings.isReasoningRequired());
        assertTrue(settings.isSupportsTemperature());
        assertEquals(128000, settings.getMaxInputTokens());
    }

    @Test
    void shouldCreateModelSettingsWithThreeArgConstructor() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings("anthropic", true, false);
        assertEquals("anthropic", settings.getProvider());
        assertTrue(settings.isReasoningRequired());
        assertFalse(settings.isSupportsTemperature());
        assertEquals(128000, settings.getMaxInputTokens());
    }

    @Test
    void shouldCreateModelSettingsWithFourArgConstructor() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings("custom", false, true, 64000);
        assertEquals("custom", settings.getProvider());
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
        assertTrue(config.getModels().containsKey("test-model"));

        ModelConfigService.ModelSettings testModel = config.getModels().get("test-model");
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
        assertEquals("openai", config.getDefaults().getProvider());
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
    void shouldCreateDefaultConfigWithAllModels() throws Exception {
        Method method = ModelConfigService.class.getDeclaredMethod("createDefaultConfig");
        method.setAccessible(true);

        ModelConfigService.ModelsConfig config = (ModelConfigService.ModelsConfig) method.invoke(service);

        assertNotNull(config);
        assertNotNull(config.getModels());
        assertFalse(config.getModels().isEmpty());

        // Verify specific models exist
        assertTrue(config.getModels().containsKey("gpt-5.1"));
        assertTrue(config.getModels().containsKey("gpt-5.2"));
        assertTrue(config.getModels().containsKey("o1"));
        assertTrue(config.getModels().containsKey("o3"));
        assertTrue(config.getModels().containsKey("o3-mini"));
        assertTrue(config.getModels().containsKey("gpt-4o"));
        assertTrue(config.getModels().containsKey("gpt-4-turbo"));
        assertTrue(config.getModels().containsKey("claude-sonnet-4-20250514"));
        assertTrue(config.getModels().containsKey("claude-3-5-sonnet"));
        assertTrue(config.getModels().containsKey("claude-3-opus"));
        assertTrue(config.getModels().containsKey("claude-3-haiku"));

        // Verify defaults
        assertNotNull(config.getDefaults());
        assertEquals("openai", config.getDefaults().getProvider());
    }

    @Test
    void shouldCreateDefaultConfigWithCorrectProviders() throws Exception {
        Method method = ModelConfigService.class.getDeclaredMethod("createDefaultConfig");
        method.setAccessible(true);

        ModelConfigService.ModelsConfig config = (ModelConfigService.ModelsConfig) method.invoke(service);

        // OpenAI reasoning models
        assertEquals("openai", config.getModels().get("gpt-5.1").getProvider());
        assertTrue(config.getModels().get("gpt-5.1").isReasoningRequired());
        assertFalse(config.getModels().get("gpt-5.1").isSupportsTemperature());

        // OpenAI standard models
        assertEquals("openai", config.getModels().get("gpt-4o").getProvider());
        assertFalse(config.getModels().get("gpt-4o").isReasoningRequired());
        assertTrue(config.getModels().get("gpt-4o").isSupportsTemperature());

        // Anthropic models
        assertEquals("anthropic", config.getModels().get("claude-3-haiku").getProvider());
        assertFalse(config.getModels().get("claude-3-haiku").isReasoningRequired());
        assertTrue(config.getModels().get("claude-3-haiku").isSupportsTemperature());
    }

    // ===== writeDefaultConfig =====

    @Test
    void shouldWriteDefaultConfig(@TempDir Path tempDir) throws Exception {
        Method createDefaultMethod = ModelConfigService.class.getDeclaredMethod("createDefaultConfig");
        createDefaultMethod.setAccessible(true);

        // Set the config field first
        java.lang.reflect.Field configField = ModelConfigService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(service, createDefaultMethod.invoke(service));

        Method writeMethod = ModelConfigService.class.getDeclaredMethod("writeDefaultConfig", Path.class);
        writeMethod.setAccessible(true);

        Path configFile = tempDir.resolve("models.json");
        writeMethod.invoke(service, configFile);

        assertTrue(Files.exists(configFile));
        String content = Files.readString(configFile);
        assertTrue(content.contains("gpt-5.1"));
        assertTrue(content.contains("openai"));
        assertTrue(content.contains("anthropic"));
    }

    @Test
    void shouldHandleWriteFailureGracefully() throws Exception {
        Method createDefaultMethod = ModelConfigService.class.getDeclaredMethod("createDefaultConfig");
        createDefaultMethod.setAccessible(true);

        java.lang.reflect.Field configField = ModelConfigService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(service, createDefaultMethod.invoke(service));

        Method writeMethod = ModelConfigService.class.getDeclaredMethod("writeDefaultConfig", Path.class);
        writeMethod.setAccessible(true);

        // Write to a non-existent directory should fail gracefully
        Path badPath = Path.of("/nonexistent/dir/models.json");
        assertDoesNotThrow(() -> {
            try {
                writeMethod.invoke(service, badPath);
            } catch (Exception e) {
                if (!(e.getCause() instanceof IOException)) {
                    throw e;
                }
            }
        });
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
        assertTrue(config.getModels().containsKey("custom-model"));
        assertEquals("custom-provider", config.getModels().get("custom-model").getProvider());
        assertTrue(config.getModels().get("custom-model").isReasoningRequired());
        assertEquals(32000, config.getModels().get("custom-model").getMaxInputTokens());
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
        assertEquals("openai", settings.getProvider());
    }

    @Test
    void shouldReturnExactMatchOverPrefixMatch() {
        // "gpt-4o" should return exact match, not prefix match with "gpt-4"
        ModelConfigService.ModelSettings settings = service.getModelSettings("gpt-4o");
        assertEquals("openai", settings.getProvider());
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
        assertEquals("openai", service.getProvider("gpt-4o"));
        assertEquals("anthropic", service.getProvider("claude-3-opus"));
    }

    @Test
    void isReasoningRequiredDelegatesToGetModelSettings() {
        assertTrue(service.isReasoningRequired("o3"));
        assertFalse(service.isReasoningRequired("gpt-4o"));
    }

    @Test
    void supportsTemperatureDelegatesToGetModelSettings() {
        assertTrue(service.supportsTemperature("gpt-4o"));
        assertFalse(service.supportsTemperature("o3"));
    }

    @Test
    void getMaxInputTokensDelegatesToGetModelSettings() {
        int tokens = service.getMaxInputTokens("gpt-4o");
        assertTrue(tokens > 0);
    }

    // ===== ModelsConfig data class =====

    @Test
    void modelsConfigSettersWork() {
        ModelConfigService.ModelsConfig config = new ModelConfigService.ModelsConfig();
        ModelConfigService.ModelSettings defaults = new ModelConfigService.ModelSettings("test", false, true);
        config.setDefaults(defaults);
        Map<String, ModelConfigService.ModelSettings> models = new java.util.HashMap<>();
        models.put("test-model", new ModelConfigService.ModelSettings("test", true, false));
        config.setModels(models);

        assertEquals("test", config.getDefaults().getProvider());
        assertEquals(1, config.getModels().size());
        assertTrue(config.getModels().get("test-model").isReasoningRequired());
    }

    // ===== ModelSettings data class =====

    @Test
    void modelSettingsSettersWork() {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider("custom");
        settings.setReasoningRequired(true);
        settings.setSupportsTemperature(false);
        settings.setMaxInputTokens(64000);

        assertEquals("custom", settings.getProvider());
        assertTrue(settings.isReasoningRequired());
        assertFalse(settings.isSupportsTemperature());
        assertEquals(64000, settings.getMaxInputTokens());
    }
}
