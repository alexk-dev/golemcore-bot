package me.golemcore.bot.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
