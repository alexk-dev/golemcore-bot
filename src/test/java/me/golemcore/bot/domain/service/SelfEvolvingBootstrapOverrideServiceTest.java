package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfEvolvingBootstrapOverrideServiceTest {

    private BotProperties botProperties;
    private SelfEvolvingBootstrapOverrideService service;

    @BeforeEach
    void setUp() {
        botProperties = new BotProperties();
        service = new SelfEvolvingBootstrapOverrideService(botProperties);
    }

    @Test
    void shouldApplyBootstrapOverridesAcrossEmbeddingsRerankAndToggles() {
        RuntimeConfig runtimeConfig = new RuntimeConfig();

        botProperties.getSelfEvolving().getBootstrap().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().setTracePayloadOverride(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().setMode("hybrid");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setProvider("ollama");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings()
                .setBaseUrl("http://localhost:11434");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setApiKey("secret");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings()
                .setModel("qwen3-embedding:0.6b");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setDimensions(768);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setBatchSize(16);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setTimeoutMs(9000);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings()
                .setAutoFallbackToBm25(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setAutoInstall(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setPullOnStart(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setRequireHealthyRuntime(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setFailOpen(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getRerank().setCrossEncoder(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getRerank().setTier("deep");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getPersonalization()
                .setEnabled(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getNegativeMemory()
                .setEnabled(false);

        service.apply(runtimeConfig);

        assertTrue(runtimeConfig.getSelfEvolving().getEnabled());
        assertTrue(runtimeConfig.getSelfEvolving().getTracePayloadOverride());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getEnabled());
        assertEquals("hybrid", runtimeConfig.getSelfEvolving().getTactics().getSearch().getMode());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getEnabled());
        assertEquals("ollama", runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getProvider());
        assertEquals("http://localhost:11434",
                runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBaseUrl());
        assertEquals("secret", runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getApiKey());
        assertEquals("qwen3-embedding:0.6b",
                runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getModel());
        assertEquals(768, runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getDimensions());
        assertEquals(16, runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBatchSize());
        assertEquals(9000, runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getTimeoutMs());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings()
                .getAutoFallbackToBm25());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getAutoInstall());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getPullOnStart());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getRequireHealthyRuntime());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().getFailOpen());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getRerank().getCrossEncoder());
        assertEquals("deep", runtimeConfig.getSelfEvolving().getTactics().getSearch().getRerank().getTier());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getPersonalization().getEnabled());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getNegativeMemory().getEnabled());
    }

    @Test
    void shouldAllowBootstrapToDisableTacticsWithoutDisablingSelfEvolving() {
        RuntimeConfig runtimeConfig = new RuntimeConfig();

        botProperties.getSelfEvolving().getBootstrap().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().setEnabled(false);

        service.apply(runtimeConfig);

        assertTrue(runtimeConfig.getSelfEvolving().getEnabled());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getEnabled());
    }

    @Test
    void shouldRestorePersistedValuesForConfiguredBootstrapFields() {
        RuntimeConfig candidateConfig = new RuntimeConfig();
        RuntimeConfig persistedConfig = new RuntimeConfig();
        persistedConfig.getSelfEvolving().setEnabled(false);
        persistedConfig.getSelfEvolving().setTracePayloadOverride(true);
        persistedConfig.getSelfEvolving().getTactics().setEnabled(false);
        persistedConfig.getSelfEvolving().getTactics().getSearch().setMode("bm25");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setEnabled(false);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setProvider("openai_compatible");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setBaseUrl("https://example.com");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setApiKey("persisted");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setModel("persisted-model");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setDimensions(1536);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setBatchSize(32);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setTimeoutMs(6000);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setAutoFallbackToBm25(true);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().setAutoInstall(false);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().setPullOnStart(false);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .setRequireHealthyRuntime(true);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().setFailOpen(true);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getRerank().setCrossEncoder(true);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getRerank().setTier("standard");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getPersonalization().setEnabled(true);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getNegativeMemory().setEnabled(true);

        botProperties.getSelfEvolving().getBootstrap().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().setTracePayloadOverride(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().setMode("hybrid");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setEnabled(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setProvider("ollama");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings()
                .setBaseUrl("http://localhost");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setApiKey("override");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setModel("override");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setDimensions(768);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setBatchSize(16);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().setTimeoutMs(9000);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings()
                .setAutoFallbackToBm25(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setAutoInstall(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setPullOnStart(true);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setRequireHealthyRuntime(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getEmbeddings().getLocal()
                .setFailOpen(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getRerank().setCrossEncoder(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getRerank().setTier("deep");
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getPersonalization()
                .setEnabled(false);
        botProperties.getSelfEvolving().getBootstrap().getTactics().getSearch().getNegativeMemory()
                .setEnabled(false);

        service.restorePersistedValues(candidateConfig, persistedConfig);

        assertFalse(candidateConfig.getSelfEvolving().getEnabled());
        assertTrue(candidateConfig.getSelfEvolving().getTracePayloadOverride());
        assertFalse(candidateConfig.getSelfEvolving().getTactics().getEnabled());
        assertEquals("bm25", candidateConfig.getSelfEvolving().getTactics().getSearch().getMode());
        assertFalse(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getEnabled());
        assertEquals("openai_compatible",
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getProvider());
        assertEquals("https://example.com",
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBaseUrl());
        assertEquals("persisted", candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings()
                .getApiKey());
        assertEquals("persisted-model",
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getModel());
        assertEquals(1536,
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getDimensions());
        assertEquals(32, candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBatchSize());
        assertEquals(6000,
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getTimeoutMs());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getAutoFallbackToBm25());
        assertFalse(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getAutoInstall());
        assertFalse(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getPullOnStart());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getRequireHealthyRuntime());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().getFailOpen());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getRerank().getCrossEncoder());
        assertEquals("standard", candidateConfig.getSelfEvolving().getTactics().getSearch().getRerank().getTier());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getPersonalization().getEnabled());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getNegativeMemory().getEnabled());
    }
}
