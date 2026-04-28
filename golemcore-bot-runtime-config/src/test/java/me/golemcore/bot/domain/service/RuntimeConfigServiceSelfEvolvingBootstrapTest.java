package me.golemcore.bot.domain.service;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.adapter.outbound.config.StorageRuntimeConfigPersistenceAdapter;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceSelfEvolvingBootstrapTest {

    private StoragePort storagePort;
    private RuntimeConfigService service;
    private ObjectMapper objectMapper;
    private Map<String, String> persistedSections;
    private RuntimeConfigTestOverrides.MutableSelfEvolvingBootstrapOverridePort bootstrapOverrides;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedSections = new ConcurrentHashMap<>();
        bootstrapOverrides = RuntimeConfigTestOverrides.mutable();

        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            String content = invocation.getArgument(2);
            persistedSections.put(fileName, content);
            return CompletableFuture.completedFuture(null);
        });
        when(storagePort.getText(anyString(), anyString())).thenAnswer(invocation -> {
            String fileName = invocation.getArgument(1);
            return CompletableFuture.completedFuture(persistedSections.get(fileName));
        });

        StorageRuntimeConfigPersistenceAdapter persistenceAdapter = new StorageRuntimeConfigPersistenceAdapter(
                storagePort);
        RuntimeConfigSnapshotProvider snapshotProvider = new RuntimeConfigSnapshotProvider();
        service = new RuntimeConfigService(persistenceAdapter, bootstrapOverrides, snapshotProvider,
                new RuntimeConfigMutationService(persistenceAdapter, snapshotProvider), new RuntimeConfigRedactor(),
                new RuntimeConfigNormalizer());
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldKeepSelfEvolvingDisabledByDefault() {
        RuntimeConfig config = service.getRuntimeConfig();

        assertFalse(config.getSelfEvolving().getEnabled());
        assertFalse(config.getSelfEvolving().getTactics().getEnabled());
        assertEquals("hybrid", config.getSelfEvolving().getTactics().getSearch().getMode());
        assertTrue(config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getEnabled());
        assertEquals("ollama", config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getProvider());
        assertEquals("http://127.0.0.1:11434",
                config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBaseUrl());
        assertTrue(config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getRequireHealthyRuntime());
        assertEquals(5000,
                config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().getStartupTimeoutMs());
        assertEquals(1000, config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getInitialRestartBackoffMs());
        assertEquals("0.19.0", config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getMinimumRuntimeVersion());
    }

    @Test
    void shouldKeepManagedLocalStartupTimeoutFixedAtFiveSeconds() throws Exception {
        Map<String, Object> local = Map.of("startupTimeoutMs", 12000, "initialRestartBackoffMs", 2500,
                "minimumRuntimeVersion", "0.20.0");
        Map<String, Object> embeddings = Map.of("enabled", true, "provider", "ollama", "baseUrl",
                "http://127.0.0.1:11434", "model", "qwen3-embedding:0.6b", "local", local);
        Map<String, Object> search = Map.of("mode", "hybrid", "embeddings", embeddings);
        Map<String, Object> tactics = Map.of("enabled", true, "search", search);
        persistedSections.put("self-evolving.json",
                objectMapper.writeValueAsString(Map.of("enabled", true, "tactics", tactics)));
        RuntimeConfig config = service.reloadRuntimeConfig();

        assertEquals(5000,
                config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal().getStartupTimeoutMs());
        assertEquals(2500, config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getInitialRestartBackoffMs());
        assertEquals("0.20.0", config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getLocal()
                .getMinimumRuntimeVersion());
    }

    @Test
    void shouldDefaultLocalEmbeddingBaseUrlWhenPersistedOllamaConfigOmitsIt() throws Exception {
        Map<String, Object> embeddings = Map.of("enabled", true, "provider", "ollama", "model", "bge-m3");
        Map<String, Object> search = Map.of("mode", "hybrid", "embeddings", embeddings);
        Map<String, Object> tactics = Map.of("enabled", true, "search", search);
        persistedSections.put("self-evolving.json",
                objectMapper.writeValueAsString(Map.of("enabled", true, "tactics", tactics)));

        RuntimeConfig config = service.reloadRuntimeConfig();

        assertEquals("http://127.0.0.1:11434",
                config.getSelfEvolving().getTactics().getSearch().getEmbeddings().getBaseUrl());
    }

    @Test
    void shouldApplyBotBootstrapOverridesOverPersistedPreferences() throws Exception {
        persistedSections.put("self-evolving.json", objectMapper.writeValueAsString(Map.of("enabled", false,
                "tracePayloadOverride", false, "tactics", Map.of("enabled", false, "search", Map.of("mode", "bm25")))));

        bootstrapOverrides.setEnabled(true);
        bootstrapOverrides.setTacticsEnabled(true);

        RuntimeConfig config = service.reloadRuntimeConfig();

        assertTrue(config.getSelfEvolving().getEnabled());
        assertTrue(config.getSelfEvolving().getTracePayloadOverride());
        assertTrue(config.getSelfEvolving().getTactics().getEnabled());
    }

    @Test
    void shouldExposeManagedMetadataForApiWhenBootstrapOverridesAreActive() {
        bootstrapOverrides.setEnabled(true);
        bootstrapOverrides.setSearchMode("hybrid");
        bootstrapOverrides.setEmbeddingsProvider("ollama");

        RuntimeConfig config = service.getRuntimeConfigForApi();

        assertTrue(config.getSelfEvolving().getManagedByProperties());
        assertIterableEquals(java.util.List.of("enabled", "tactics.search.mode", "tactics.search.embeddings.provider"),
                config.getSelfEvolving().getOverriddenPaths());
    }

    @Test
    void shouldKeepBootstrapOverridesOutOfPersistedPreferences() throws Exception {
        persistedSections.put("self-evolving.json", objectMapper.writeValueAsString(Map.of("enabled", false,
                "tracePayloadOverride", false, "tactics", Map.of("enabled", false, "search", Map.of("mode", "bm25")))));

        bootstrapOverrides.setEnabled(true);
        bootstrapOverrides.setTacticsEnabled(true);

        RuntimeConfig config = service.reloadRuntimeConfig();
        service.updateRuntimeConfig(config);

        Map<?, ?> persistedSelfEvolving = objectMapper.readValue(persistedSections.get("self-evolving.json"),
                Map.class);
        assertEquals(false, persistedSelfEvolving.get("enabled"));
        assertEquals(true, persistedSelfEvolving.get("tracePayloadOverride"));

        Map<?, ?> persistedTactics = (Map<?, ?>) persistedSelfEvolving.get("tactics");
        assertEquals(false, persistedTactics.get("enabled"));
    }
}
