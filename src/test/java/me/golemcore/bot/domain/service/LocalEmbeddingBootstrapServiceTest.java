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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalEmbeddingBootstrapServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-02T00:30:00Z"), ZoneOffset.UTC);

    private RuntimeConfigService runtimeConfigService;
    private TacticSearchMetricsService metricsService;
    private TestLocalEmbeddingBootstrapService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        metricsService = new TacticSearchMetricsService(FIXED_CLOCK);
        service = new TestLocalEmbeddingBootstrapService(runtimeConfigService, metricsService);
    }

    @Test
    void shouldStayBm25WhenSelfEvolvingTacticsAreDisabled() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(false, true, "hybrid", true,
                "ollama", true, true, false, true, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("selfevolving tactics disabled", status.getReason());
        assertFalse(status.getDegraded());
    }

    @Test
    void shouldStayBm25WhenEmbeddingsAreDisabled() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", false,
                "ollama", true, true, true, true, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("embeddings disabled", status.getReason());
        assertFalse(status.getDegraded());
    }

    @Test
    void shouldUseHybridForConfiguredNonOllamaProvider() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "openai_compatible", true, false, false, false, "https://embeddings.example",
                "text-embedding-3-large"));

        TacticSearchStatus status = service.initialize();

        assertEquals("hybrid", status.getMode());
        assertEquals("openai_compatible", status.getProvider());
        assertTrue(status.getRuntimeHealthy());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldDegradeWhenLocalRuntimeIsRequiredButUnavailable() {
        service.runtimeHealthy = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, true, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("local embedding runtime unavailable", status.getReason());
        assertTrue(status.getDegraded());
        assertEquals(1L, metricsService.snapshot().fallbackCount());
    }

    @Test
    void shouldThrowWhenEmbeddingProviderConfigurationIsIncompleteAndFailOpenIsDisabled() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", false, false, false, false, null, "qwen3-embedding:0.6b"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.initialize());

        assertEquals("embedding provider configuration incomplete", error.getMessage());
    }

    @Test
    void shouldAttemptPullAndRecoverHybridModeWhenLocalModelBecomesAvailable() {
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(true);
        service.pullResult = true;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, true, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("hybrid", status.getMode());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldDegradeWhenPullFailsButFailOpenIsEnabled() {
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(false);
        service.pullResult = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, true, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("local embedding model pull failed", status.getReason());
        assertTrue(status.getPullAttempted());
        assertFalse(status.getPullSucceeded());
        assertTrue(status.getDegraded());
    }

    @Test
    void shouldInstallConfiguredLocalModelOnDemand() {
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(true);
        service.pullResult = true;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.installConfiguredModel();

        assertEquals("hybrid", status.getMode());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    private RuntimeConfig.SelfEvolvingConfig config(
            boolean selfEvolvingEnabled,
            boolean tacticsEnabled,
            String mode,
            boolean embeddingsEnabled,
            String provider,
            boolean failOpen,
            boolean autoInstall,
            boolean requireHealthyRuntime,
            boolean pullOnStart,
            String baseUrl,
            String model) {
        return RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(selfEvolvingEnabled)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(tacticsEnabled)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode(mode)
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(embeddingsEnabled)
                                        .provider(provider)
                                        .baseUrl(baseUrl)
                                        .model(model)
                                        .local(RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig.builder()
                                                .failOpen(failOpen)
                                                .autoInstall(autoInstall)
                                                .requireHealthyRuntime(requireHealthyRuntime)
                                                .pullOnStart(pullOnStart)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static final class TestLocalEmbeddingBootstrapService extends LocalEmbeddingBootstrapService {

        private boolean runtimeHealthy = true;
        private final Deque<Boolean> hasModelResponses = new ArrayDeque<>();
        private boolean pullResult;

        private TestLocalEmbeddingBootstrapService(
                RuntimeConfigService runtimeConfigService,
                TacticSearchMetricsService metricsService) {
            super(runtimeConfigService, metricsService, FIXED_CLOCK, new okhttp3.OkHttpClient(),
                    new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        protected boolean isRuntimeHealthy(String baseUrl) {
            return runtimeHealthy;
        }

        @Override
        protected boolean hasModel(String baseUrl, String model) {
            return hasModelResponses.isEmpty() ? false : hasModelResponses.removeFirst();
        }

        @Override
        protected boolean pullModel(String baseUrl, String model) {
            return pullResult;
        }
    }
}
