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
    void shouldReportWhenOllamaIsNotInstalled() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(false, null);
        service.runtimeHealthy = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("Ollama is not installed on this machine", status.getReason());
        assertFalse(status.getRuntimeInstalled());
        assertFalse(status.getRuntimeHealthy());
        assertEquals("http://localhost:11434", status.getBaseUrl());
    }

    @Test
    void shouldReportWhenOllamaIsInstalledButNotRunning() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("Ollama is installed but not running at http://localhost:11434", status.getReason());
        assertTrue(status.getRuntimeInstalled());
        assertFalse(status.getRuntimeHealthy());
        assertEquals("0.19.0", status.getRuntimeVersion());
    }

    @Test
    void shouldTreatHealthyRuntimeAsInstalledEvenWhenBinaryProbeFails() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(false, null);
        service.runtimeHealthy = true;
        service.hasModelResponses.add(true);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.probeStatus();

        assertEquals("hybrid", status.getMode());
        assertTrue(status.getRuntimeInstalled());
        assertTrue(status.getRuntimeHealthy());
        assertTrue(status.getModelAvailable());
        assertEquals("qwen3-embedding:0.6b", status.getModel());
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
        assertEquals("Ollama is installed but not running at http://localhost:11434", status.getReason());
        assertTrue(status.getDegraded());
        assertEquals(1L, metricsService.snapshot().fallbackCount());
    }

    @Test
    void shouldThrowWhenEmbeddingModelConfigurationIsIncompleteAndFailOpenIsDisabled() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", false, false, false, false, null, null));

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
        assertEquals("Failed to install embedding model qwen3-embedding:0.6b in Ollama", status.getReason());
        assertTrue(status.getPullAttempted());
        assertFalse(status.getPullSucceeded());
        assertTrue(status.getDegraded());
    }

    @Test
    void shouldInstallConfiguredLocalModelOnDemand() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
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

    @Test
    void shouldInstallRequestedLocalModelOnDemand() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(true);
        service.pullResult = true;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, false, null, "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.installModel("bge-m3");

        assertEquals("bge-m3", status.getModel());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldInstallRequestedLocalModelWhenProviderIsNotPersistedYet() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(true);
        service.pullResult = true;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                null, true, true, false, false, null, null));

        TacticSearchStatus status = service.installModel("qwen3-embedding:0.6b");

        assertEquals("ollama", status.getProvider());
        assertEquals("qwen3-embedding:0.6b", status.getModel());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldInstallModelWhenRuntimeIsHealthyButBinaryProbeFails() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(false, null);
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(true);
        service.pullResult = true;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.installModel("qwen3-embedding:0.6b");

        assertEquals("hybrid", status.getMode());
        assertTrue(status.getRuntimeInstalled());
        assertTrue(status.getRuntimeHealthy());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldReturnSupervisorBackedProjectionWhenAvailable() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .reason("Managed Ollama exited with code 137")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .degraded(true)
                .runtimeState("degraded_restart_backoff")
                .owned(true)
                .restartAttempts(2)
                .nextRetryTime("2026-04-02T00:31:00Z")
                .runtimeInstalled(true)
                .runtimeHealthy(false)
                .runtimeVersion("0.19.0")
                .baseUrl("http://127.0.0.1:11434")
                .modelAvailable(false)
                .updatedAt(Instant.parse("2026-04-02T00:30:00Z"))
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new okhttp3.OkHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                projectionService);

        TacticSearchStatus status = projectedService.probeStatus();

        assertEquals("degraded_restart_backoff", status.getRuntimeState());
        assertTrue(status.getOwned());
        assertEquals(Integer.valueOf(2), status.getRestartAttempts());
        assertEquals("2026-04-02T00:31:00Z", status.getNextRetryTime());
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

        private LocalEmbeddingBootstrapService.LocalRuntimeProbe runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(
                true, "0.19.0");
        private boolean runtimeHealthy = true;
        private final Deque<Boolean> hasModelResponses = new ArrayDeque<>();
        private boolean pullResult;

        private TestLocalEmbeddingBootstrapService(
                RuntimeConfigService runtimeConfigService,
                TacticSearchMetricsService metricsService) {
            super(runtimeConfigService, metricsService, FIXED_CLOCK, new okhttp3.OkHttpClient(),
                    new com.fasterxml.jackson.databind.ObjectMapper(), null);
        }

        @Override
        protected boolean isRuntimeHealthy(String baseUrl) {
            return runtimeHealthy;
        }

        @Override
        protected LocalRuntimeProbe probeLocalRuntime(String provider) {
            return runtimeProbe;
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
