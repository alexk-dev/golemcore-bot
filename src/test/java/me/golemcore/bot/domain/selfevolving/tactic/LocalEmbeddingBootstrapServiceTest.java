package me.golemcore.bot.domain.selfevolving.tactic;

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
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeModelPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.selfevolving.SelfEvolvingTacticSearchStatusProjectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;

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
    void shouldPreviewUnsavedLocalModelSelectionAgainstLiveRuntime() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = true;
        service.hasModelResponses.add(true);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(false, false, "bm25", false,
                null, true, false, false, true, null, null));

        TacticSearchStatus status = service.probeStatus("ollama", "bge-m3", null);

        assertEquals("hybrid", status.getMode());
        assertEquals("ollama", status.getProvider());
        assertEquals("bge-m3", status.getModel());
        assertEquals("http://127.0.0.1:11434", status.getBaseUrl());
        assertTrue(status.getRuntimeInstalled());
        assertTrue(status.getRuntimeHealthy());
        assertTrue(status.getModelAvailable());
        assertFalse(Boolean.TRUE.equals(status.getDegraded()));
    }

    @Test
    void shouldStayBm25WhenSelfEvolvingTacticsAreDisabled() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, false, "hybrid", true,
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
    void shouldUseDefaultOllamaBaseUrlWhenConfigDoesNotSetOne() {
        service.runtimeHealthy = true;
        service.hasModelResponses.add(true);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, null, "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.probeStatus();

        assertEquals("hybrid", status.getMode());
        assertEquals("http://127.0.0.1:11434", status.getBaseUrl());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldStayBm25WhenLexicalOnlyModeIsConfigured() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "bm25", true,
                "ollama", true, false, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("lexical-only mode configured", status.getReason());
        assertFalse(status.getDegraded());
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
    void shouldDegradeWhenEmbeddingModelConfigurationIsIncompleteAndFailOpenIsEnabled() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, null, null));

        TacticSearchStatus status = service.initialize();

        assertEquals("bm25", status.getMode());
        assertEquals("embedding provider configuration incomplete", status.getReason());
        assertTrue(status.getDegraded());
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
    void shouldThrowWhenConfiguredInstallProviderIsNotLocal() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "openai_compatible", true, false, false, false, "https://embeddings.example",
                "text-embedding-3-large"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.installConfiguredModel());

        assertEquals("local embedding provider is not configured", error.getMessage());
    }

    @Test
    void shouldThrowWhenConfiguredInstallModelIsMissing() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, null, null));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.installConfiguredModel());

        assertEquals("embedding provider configuration incomplete", error.getMessage());
    }

    @Test
    void shouldThrowWhenDirectInstallCannotPullRequestedModel() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = true;
        service.hasModelResponses.add(false);
        service.hasModelResponses.add(false);
        service.pullResult = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.installConfiguredModel());

        assertEquals("Failed to install embedding model qwen3-embedding:0.6b in Ollama", error.getMessage());
    }

    @Test
    void shouldReturnExistingConfiguredModelWithoutPullWhenAlreadyInstalled() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = true;
        service.hasModelResponses.add(true);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TacticSearchStatus status = service.installConfiguredModel();

        assertEquals("hybrid", status.getMode());
        assertFalse(status.getPullAttempted());
        assertFalse(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldThrowWhenConfiguredLocalRuntimeIsMissingDuringInstall() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(false, null);
        service.runtimeHealthy = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.installConfiguredModel());

        assertEquals("Ollama is not installed on this machine. Install Ollama first, then retry model installation.",
                error.getMessage());
    }

    @Test
    void shouldThrowWhenConfiguredLocalRuntimeIsNotRunningDuringInstall() {
        service.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        service.runtimeHealthy = false;
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, "http://localhost:11434", "qwen3-embedding:0.6b"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.installConfiguredModel());

        assertEquals(
                "Ollama is installed but not running at http://localhost:11434. Start the runtime, then retry model installation.",
                error.getMessage());
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
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort());

        TacticSearchStatus status = projectedService.probeStatus();

        assertEquals("degraded_restart_backoff", status.getRuntimeState());
        assertTrue(status.getOwned());
        assertEquals(Integer.valueOf(2), status.getRestartAttempts());
        assertEquals("2026-04-02T00:31:00Z", status.getNextRetryTime());
    }

    @Test
    void shouldRecordProjectedStatusDuringInitializationWhenProjectionIsAvailable() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("hybrid")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort());

        TacticSearchStatus status = projectedService.initialize();

        assertEquals("hybrid", status.getMode());
        assertEquals("hybrid", metricsService.snapshot().activeMode());
    }

    @Test
    void shouldRunStartupPullBeforeReturningProjectedStatusWhenProjectionIsAvailable() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .modelAvailable(false)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, true, false, true, "http://localhost:11434", "qwen3-embedding:0.6b"));

        TestLocalEmbeddingBootstrapService projectedService = new TestLocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                projectionService);
        projectedService.runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(true, "0.19.0");
        projectedService.runtimeHealthy = true;
        projectedService.hasModelResponses.add(false);
        projectedService.hasModelResponses.add(true);
        projectedService.hasModelResponses.add(true);
        projectedService.pullResult = true;

        TacticSearchStatus status = projectedService.initialize();

        assertEquals("hybrid", status.getMode());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldTreatNonOllamaRuntimeProbeAsNotApplicable() {
        LocalEmbeddingBootstrapService directService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                null,
                new StubOllamaProcessPort());
        LocalEmbeddingBootstrapService.LocalRuntimeProbe probe = directService.probeLocalRuntime("openai_compatible");

        assertFalse(probe.installed());
        assertEquals(null, probe.version());
    }

    @Test
    void shouldTreatMissingOllamaProcessPortAsMissingRuntime() {
        LocalEmbeddingBootstrapService directService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                null,
                null);

        LocalEmbeddingBootstrapService.LocalRuntimeProbe probe = directService.probeLocalRuntime("ollama");

        assertFalse(probe.installed());
        assertEquals(null, probe.version());
    }

    @Test
    void shouldNormalizeVersionFromOllamaProcessProbe() {
        LocalEmbeddingBootstrapService directService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                null,
                new OllamaProcessPort() {
                    @Override
                    public boolean isBinaryAvailable() {
                        return true;
                    }

                    @Override
                    public String getInstalledVersion() {
                        return "ollama version 0.19.0\nbuild abc";
                    }

                    @Override
                    public void startServe(String endpoint) {
                    }

                    @Override
                    public boolean isOwnedProcessAlive() {
                        return false;
                    }

                    @Override
                    public Integer getOwnedProcessExitCode() {
                        return null;
                    }

                    @Override
                    public void stopOwnedProcess() {
                    }
                });

        LocalEmbeddingBootstrapService.LocalRuntimeProbe probe = directService.probeLocalRuntime("ollama");

        assertTrue(probe.installed());
        assertEquals("0.19.0", probe.version());
    }

    @Test
    void shouldTreatBlankInstalledVersionAsMissingRuntimeProbe() {
        LocalEmbeddingBootstrapService directService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                null,
                new OllamaProcessPort() {
                    @Override
                    public boolean isBinaryAvailable() {
                        return true;
                    }

                    @Override
                    public String getInstalledVersion() {
                        return "   ";
                    }

                    @Override
                    public void startServe(String endpoint) {
                    }

                    @Override
                    public boolean isOwnedProcessAlive() {
                        return false;
                    }

                    @Override
                    public Integer getOwnedProcessExitCode() {
                        return null;
                    }

                    @Override
                    public void stopOwnedProcess() {
                    }
                });

        LocalEmbeddingBootstrapService.LocalRuntimeProbe probe = directService.probeLocalRuntime("ollama");

        assertFalse(probe.installed());
        assertEquals(null, probe.version());
    }

    @Test
    void shouldThrowProjectedInstallReasonWhenRuntimeIsMissing() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .reason("Ollama is not installed on this machine")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeInstalled(false)
                .runtimeHealthy(false)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, null, "qwen3-embedding:0.6b"));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> projectedService.installConfiguredModel());

        assertEquals("Ollama is not installed on this machine", error.getMessage());
    }

    @Test
    void shouldReuseProjectedHealthyStatusWhenModelIsAlreadyAvailable() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .baseUrl("http://127.0.0.1:11434")
                .modelAvailable(false)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, null, "qwen3-embedding:0.6b"));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort()) {
            @Override
            protected boolean hasModel(String baseUrl, String model) {
                return true;
            }
        };

        TacticSearchStatus status = projectedService.installConfiguredModel();

        assertEquals("hybrid", status.getMode());
        assertEquals("http://127.0.0.1:11434", status.getBaseUrl());
        assertTrue(status.getModelAvailable());
        assertFalse(status.getPullAttempted());
        assertFalse(status.getPullSucceeded());
    }

    @Test
    void shouldRefreshProjectedStatusAfterConfiguredModelInstallSucceeds() {
        ManagedLocalOllamaSupervisor supervisor = mock(ManagedLocalOllamaSupervisor.class);
        when(supervisor.currentStatus()).thenReturn(ManagedLocalOllamaStatus.builder()
                .currentState(me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState.OWNED_READY)
                .owned(true)
                .endpoint("http://127.0.0.1:11434")
                .version("0.19.0")
                .selectedModel("qwen3-embedding:0.6b")
                .modelPresent(false)
                .updatedAt(Instant.parse("2026-04-02T00:29:00Z"))
                .build());
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, "http://127.0.0.1:11434", "qwen3-embedding:0.6b"));

        SelfEvolvingTacticSearchStatusProjectionService projectionService = new SelfEvolvingTacticSearchStatusProjectionService(
                runtimeConfigService,
                supervisor,
                FIXED_CLOCK);

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort()) {
            private final Deque<Boolean> modelChecks = new ArrayDeque<>(java.util.List.of(false, true, true));

            @Override
            protected LocalRuntimeProbe probeLocalRuntime(String provider) {
                return new LocalRuntimeProbe(true, "0.19.0");
            }

            @Override
            protected boolean isRuntimeHealthy(String baseUrl) {
                return true;
            }

            @Override
            protected boolean hasModel(String baseUrl, String model) {
                return modelChecks.isEmpty() ? true : modelChecks.removeFirst();
            }

            @Override
            protected boolean pullModel(String baseUrl, String model) {
                return true;
            }
        };

        TacticSearchStatus installStatus = projectedService.installConfiguredModel();
        TacticSearchStatus refreshedStatus = projectedService.probeStatus();

        assertTrue(installStatus.getModelAvailable());
        assertEquals("hybrid", refreshedStatus.getMode());
        assertTrue(refreshedStatus.getModelAvailable());
        assertFalse(Boolean.TRUE.equals(refreshedStatus.getDegraded()));
    }

    @Test
    void shouldThrowProjectedInstallReasonWhenRuntimeIsUnhealthy() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .reason("Ollama is installed but not running at http://127.0.0.1:11434")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeInstalled(true)
                .runtimeHealthy(false)
                .baseUrl("http://127.0.0.1:11434")
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, true, false, null, "qwen3-embedding:0.6b"));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> projectedService.installConfiguredModel());

        assertEquals("Ollama is installed but not running at http://127.0.0.1:11434", error.getMessage());
    }

    @Test
    void shouldThrowWhenProjectedInstallModelConfigurationIsIncomplete() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .provider("ollama")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, null, null));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> projectedService.installConfiguredModel());

        assertEquals("embedding provider configuration incomplete", error.getMessage());
    }

    @Test
    void shouldThrowWhenProjectedProviderIsNotConfiguredAndNoExplicitModelIsProvided() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                null, true, false, false, false, null, null));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> projectedService.installConfiguredModel());

        assertEquals("local embedding provider is not configured", error.getMessage());
    }

    @Test
    void shouldThrowWhenProjectedModelInstallFails() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .provider("ollama")
                .model("qwen3-embedding:0.6b")
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .baseUrl("http://127.0.0.1:11434")
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                "ollama", true, false, false, false, null, "qwen3-embedding:0.6b"));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort()) {
            @Override
            protected boolean hasModel(String baseUrl, String model) {
                return false;
            }

            @Override
            protected boolean pullModel(String baseUrl, String model) {
                return false;
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> projectedService.installConfiguredModel());

        assertEquals("Failed to install embedding model qwen3-embedding:0.6b in Ollama", error.getMessage());
    }

    @Test
    void shouldInstallExplicitProjectedModelUsingDefaultLocalBaseUrl() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .reason(null)
                .runtimeInstalled(true)
                .runtimeHealthy(true)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(true, true, "hybrid", true,
                null, true, true, false, false, null, null));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort()) {
            private int checks;

            @Override
            protected boolean isRuntimeHealthy(String baseUrl) {
                return true;
            }

            @Override
            protected boolean hasModel(String baseUrl, String model) {
                checks++;
                return checks > 1;
            }

            @Override
            protected boolean pullModel(String baseUrl, String model) {
                return true;
            }
        };

        TacticSearchStatus status = projectedService.installModel("bge-m3");

        assertEquals("ollama", status.getProvider());
        assertEquals("bge-m3", status.getModel());
        assertEquals("http://127.0.0.1:11434", status.getBaseUrl());
        assertTrue(status.getPullAttempted());
        assertTrue(status.getPullSucceeded());
        assertTrue(status.getModelAvailable());
    }

    @Test
    void shouldInstallExplicitProjectedModelWhenSavedConfigStillLooksDisabled() {
        SelfEvolvingTacticSearchStatusProjectionService projectionService = mock(
                SelfEvolvingTacticSearchStatusProjectionService.class);
        TacticSearchStatus projectedStatus = TacticSearchStatus.builder()
                .mode("bm25")
                .reason("selfevolving tactics disabled")
                .runtimeInstalled(false)
                .runtimeHealthy(false)
                .build();
        when(projectionService.projectCurrent()).thenReturn(projectedStatus);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config(false, false, "bm25", false,
                null, true, true, false, false, null, null));

        LocalEmbeddingBootstrapService projectedService = new LocalEmbeddingBootstrapService(
                runtimeConfigService,
                metricsService,
                FIXED_CLOCK,
                new StubOllamaRuntimeApiPort(),
                new StubOllamaRuntimeApiPort(),
                projectionService,
                new StubOllamaProcessPort()) {
            private int checks;

            @Override
            protected boolean isRuntimeHealthy(String baseUrl) {
                return true;
            }

            @Override
            protected boolean hasModel(String baseUrl, String model) {
                checks++;
                return checks > 1;
            }

            @Override
            protected boolean pullModel(String baseUrl, String model) {
                return true;
            }
        };

        TacticSearchStatus status = projectedService.installModel("bge-m3");

        assertEquals("ollama", status.getProvider());
        assertEquals("bge-m3", status.getModel());
        assertEquals("http://127.0.0.1:11434", status.getBaseUrl());
        assertTrue(status.getRuntimeInstalled());
        assertTrue(status.getRuntimeHealthy());
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

        private LocalEmbeddingBootstrapService.LocalRuntimeProbe runtimeProbe = new LocalEmbeddingBootstrapService.LocalRuntimeProbe(
                true, "0.19.0");
        private boolean runtimeHealthy = true;
        private final Deque<Boolean> hasModelResponses = new ArrayDeque<>();
        private boolean pullResult;

        private TestLocalEmbeddingBootstrapService(
                RuntimeConfigService runtimeConfigService,
                TacticSearchMetricsService metricsService) {
            this(runtimeConfigService, metricsService, null);
        }

        private TestLocalEmbeddingBootstrapService(
                RuntimeConfigService runtimeConfigService,
                TacticSearchMetricsService metricsService,
                SelfEvolvingTacticSearchStatusProjectionService projectionService) {
            super(runtimeConfigService, metricsService, FIXED_CLOCK, new StubOllamaRuntimeApiPort(),
                    new StubOllamaRuntimeApiPort(), projectionService, new StubOllamaProcessPort());
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

    private static final class StubOllamaProcessPort implements OllamaProcessPort {

        @Override
        public boolean isBinaryAvailable() {
            return true;
        }

        @Override
        public String getInstalledVersion() {
            return "0.19.0";
        }

        @Override
        public void startServe(String endpoint) {
        }

        @Override
        public boolean isOwnedProcessAlive() {
            return false;
        }

        @Override
        public Integer getOwnedProcessExitCode() {
            return null;
        }

        @Override
        public void stopOwnedProcess() {
        }
    }

    private static final class StubOllamaRuntimeApiPort implements OllamaRuntimeProbePort, OllamaRuntimeModelPort {

        @Override
        public boolean isRuntimeReachable(String endpoint, java.time.Duration timeout) {
            return false;
        }

        @Override
        public String getRuntimeVersion(String endpoint, java.time.Duration timeout) {
            return null;
        }

        @Override
        public boolean hasModel(String endpoint, String model, java.time.Duration timeout) {
            return false;
        }

        @Override
        public boolean pullModel(String endpoint, String model, java.time.Duration timeout) {
            return false;
        }
    }
}
