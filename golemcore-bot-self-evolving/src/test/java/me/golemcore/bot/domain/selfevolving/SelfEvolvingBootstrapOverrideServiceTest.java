package me.golemcore.bot.domain.selfevolving;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.SelfEvolvingBootstrapSettingsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelfEvolvingBootstrapOverrideServiceTest {

    private SelfEvolvingBootstrapSettingsPort settingsPort;
    private SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings bootstrapSettings;
    private SelfEvolvingBootstrapOverrideService service;

    @BeforeEach
    void setUp() {
        bootstrapSettings = new SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings(null, null);
        settingsPort = mock(SelfEvolvingBootstrapSettingsPort.class);
        when(settingsPort.selfEvolvingBootstrap()).thenAnswer(invocation -> bootstrapSettings);
        service = new SelfEvolvingBootstrapOverrideService(settingsPort);
    }

    @Test
    void shouldApplyBootstrapOverridesAcrossEmbeddingsRerankAndToggles() {
        RuntimeConfig runtimeConfig = new RuntimeConfig();
        bootstrapSettings = createOverrideBootstrapSettings();

        service.apply(runtimeConfig);

        assertTrue(runtimeConfig.getSelfEvolving().getEnabled());
        assertTrue(runtimeConfig.getSelfEvolving().getTracePayloadOverride());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getEnabled());
        assertEquals("hybrid", runtimeConfig.getSelfEvolving().getTactics().getSearch().getMode());
        assertTrue(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getEnabled());
        assertEquals("ollama", runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getProvider());
        assertEquals("http://localhost:11434",
                runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBaseUrl());
        assertEquals("secret",
                Secret.valueOrEmpty(
                        runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getApiKey()));
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
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getFailOpen());
        assertEquals(7000, runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getStartupTimeoutMs());
        assertEquals(2500, runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getInitialRestartBackoffMs());
        assertEquals("0.20.0", runtimeConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getMinimumRuntimeVersion());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getPersonalization().getEnabled());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getSearch().getNegativeMemory().getEnabled());
    }

    @Test
    void shouldAllowBootstrapToDisableTacticsWithoutDisablingSelfEvolving() {
        RuntimeConfig runtimeConfig = new RuntimeConfig();
        bootstrapSettings = new SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings(
                true,
                new SelfEvolvingBootstrapSettingsPort.TacticsSettings(false, null));

        service.apply(runtimeConfig);

        assertTrue(runtimeConfig.getSelfEvolving().getEnabled());
        assertFalse(runtimeConfig.getSelfEvolving().getTactics().getEnabled());
    }

    @Test
    void shouldRestorePersistedValuesForConfiguredBootstrapFields() {
        RuntimeConfig candidateConfig = new RuntimeConfig();
        RuntimeConfig persistedConfig = createPersistedConfig();
        bootstrapSettings = createOverrideBootstrapSettings();

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
        assertEquals("persisted", Secret.valueOrEmpty(
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getApiKey()));
        assertEquals("persisted-model",
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getModel());
        assertEquals(1536,
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getDimensions());
        assertEquals(32, candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBatchSize());
        assertEquals(6000,
                candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getTimeoutMs());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings()
                .getAutoFallbackToBm25());
        assertFalse(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getAutoInstall());
        assertFalse(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getPullOnStart());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getRequireHealthyRuntime());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getFailOpen());
        assertEquals(5000, candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getStartupTimeoutMs());
        assertEquals(1000, candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getInitialRestartBackoffMs());
        assertEquals("0.19.0", candidateConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getMinimumRuntimeVersion());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getPersonalization().getEnabled());
        assertTrue(candidateConfig.getSelfEvolving().getTactics().getSearch().getNegativeMemory().getEnabled());
    }

    private RuntimeConfig createPersistedConfig() {
        RuntimeConfig persistedConfig = new RuntimeConfig();
        persistedConfig.getSelfEvolving().setEnabled(false);
        persistedConfig.getSelfEvolving().setTracePayloadOverride(true);
        persistedConfig.getSelfEvolving().getTactics().setEnabled(false);
        persistedConfig.getSelfEvolving().getTactics().getSearch().setMode("bm25");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setEnabled(false);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setProvider("openai_compatible");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setBaseUrl("https://example.com");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().setApiKey(Secret.of("persisted"));
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
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().setStartupTimeoutMs(5000);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .setInitialRestartBackoffMs(1000);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .setMinimumRuntimeVersion("0.19.0");
        persistedConfig.getSelfEvolving().getTactics().getSearch().getPersonalization().setEnabled(true);
        persistedConfig.getSelfEvolving().getTactics().getSearch().getNegativeMemory().setEnabled(true);
        return persistedConfig;
    }

    private SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings createOverrideBootstrapSettings() {
        return new SelfEvolvingBootstrapSettingsPort.SelfEvolvingBootstrapSettings(
                true,
                new SelfEvolvingBootstrapSettingsPort.TacticsSettings(
                        true,
                        new SelfEvolvingBootstrapSettingsPort.SearchSettings(
                                "hybrid",
                                new SelfEvolvingBootstrapSettingsPort.EmbeddingsSettings(
                                        "ollama",
                                        "http://localhost:11434",
                                        "secret",
                                        "qwen3-embedding:0.6b",
                                        768,
                                        16,
                                        9000,
                                        new SelfEvolvingBootstrapSettingsPort.LocalEmbeddingsSettings(
                                                true,
                                                true,
                                                false,
                                                false,
                                                7000,
                                                2500,
                                                "0.20.0")),
                                new SelfEvolvingBootstrapSettingsPort.ToggleSettings(false),
                                new SelfEvolvingBootstrapSettingsPort.ToggleSettings(false))));
    }
}
