package me.golemcore.bot.infrastructure.lifecycle;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.service.ManagedLocalOllamaSupervisor;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedLocalOllamaLifecycleBridgeTest {

    private RuntimeConfigService runtimeConfigService;
    private RecordingSupervisor supervisor;
    private ManagedLocalOllamaLifecycleBridge bridge;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        supervisor = new RecordingSupervisor();
        bridge = new ManagedLocalOllamaLifecycleBridge(runtimeConfigService, supervisor);
    }

    @Test
    void shouldBlockStartupForAtMostFiveSecondsBeforeProceeding() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertTrue(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldStopOwnedRuntimeOnShutdown() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));

        bridge.runStartupGate();
        bridge.stop();

        assertTrue(supervisor.stopInvoked);
    }

    @Test
    void shouldNotManageNonLocalOllamaEndpoints() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(remoteOllamaConfig());

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertFalse(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldTreatMissingBaseUrlAsLocalManagedEndpoint() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsWithoutBaseUrl());

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertTrue(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldNotRunStartupGateTwiceAfterInitialization() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));

        bridge.initialize();
        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertTrue(bridge.isStarted());
    }

    @Test
    void shouldPollOwnedRuntimeAndObserveRecoveryAfterStartupTimeout() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, true);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.pollOwnedProcessInvocations);
        assertEquals(1, supervisor.observeReadinessInvocations);
        assertEquals(0, supervisor.pollExternalRuntimeInvocations);
        assertEquals(0, supervisor.attemptScheduledRetryInvocations);
    }

    @Test
    void shouldAttemptScheduledRetryAfterOwnedCrashDetection() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.OWNED_READY, true);
        supervisor.pollOwnedProcessStatus = status(ManagedLocalOllamaState.DEGRADED_CRASHED, true);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.pollOwnedProcessInvocations);
        assertEquals(1, supervisor.attemptScheduledRetryInvocations);
        assertEquals(0, supervisor.observeReadinessInvocations);
    }

    @Test
    void shouldPollExternalRuntimeWhenUsingExternalOwnership() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.EXTERNAL_READY, false);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.pollExternalRuntimeInvocations);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
        assertEquals(0, supervisor.startupCheckInvocations);
    }

    @Test
    void shouldRetryExternalRecoveryWithoutTakingOwnership() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST, false);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertTrue(supervisor.lastLocalEmbeddingsActive);
        assertEquals(0, supervisor.pollExternalRuntimeInvocations);
    }

    @Test
    void shouldMarkSupervisorDisabledWhenManagedLocalEmbeddingsAreInactive() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(false));
        supervisor.currentStatus = status(ManagedLocalOllamaState.OWNED_READY, true);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.embeddingsDisabledInvocations);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
        assertEquals(0, supervisor.pollExternalRuntimeInvocations);
        assertEquals(0, supervisor.attemptScheduledRetryInvocations);
    }

    private static ManagedLocalOllamaStatus status(ManagedLocalOllamaState state, boolean owned) {
        return ManagedLocalOllamaStatus.builder()
                .currentState(state)
                .owned(owned)
                .build();
    }

    private RuntimeConfig.SelfEvolvingConfig localEmbeddingsConfig(boolean enabled) {
        return RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(enabled)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(enabled)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(enabled)
                                        .provider("ollama")
                                        .baseUrl("http://127.0.0.1:11434")
                                        .model("qwen3-embedding:0.6b")
                                        .local(RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig.builder()
                                                .requireHealthyRuntime(true)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private RuntimeConfig.SelfEvolvingConfig remoteOllamaConfig() {
        return RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(true)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(true)
                                        .provider("ollama")
                                        .baseUrl("https://ollama.example.com")
                                        .model("qwen3-embedding:0.6b")
                                        .local(RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig.builder()
                                                .requireHealthyRuntime(true)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private RuntimeConfig.SelfEvolvingConfig localEmbeddingsWithoutBaseUrl() {
        return RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .tactics(RuntimeConfig.SelfEvolvingTacticsConfig.builder()
                        .enabled(true)
                        .search(RuntimeConfig.SelfEvolvingTacticSearchConfig.builder()
                                .mode("hybrid")
                                .embeddings(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig.builder()
                                        .enabled(true)
                                        .provider("ollama")
                                        .model("qwen3-embedding:0.6b")
                                        .local(RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig.builder()
                                                .requireHealthyRuntime(true)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static final class RecordingSupervisor extends ManagedLocalOllamaSupervisor {

        private int startupCheckInvocations;
        private boolean lastLocalEmbeddingsActive;
        private boolean stopInvoked;
        private int embeddingsDisabledInvocations;
        private int pollOwnedProcessInvocations;
        private int observeReadinessInvocations;
        private int pollExternalRuntimeInvocations;
        private int attemptScheduledRetryInvocations;
        private ManagedLocalOllamaStatus currentStatus = ManagedLocalOllamaStatus.builder()
                .currentState(ManagedLocalOllamaState.DISABLED)
                .owned(false)
                .build();
        private ManagedLocalOllamaStatus pollOwnedProcessStatus;

        private RecordingSupervisor() {
            super(
                    Clock.fixed(Instant.parse("2026-04-02T03:00:00Z"), ZoneOffset.UTC),
                    new NoopRuntimeProbePort(),
                    new NoopProcessPort(),
                    "http://127.0.0.1:11434",
                    "0.19.0",
                    "qwen3-embedding:0.6b");
        }

        @Override
        public ManagedLocalOllamaStatus startupCheck(boolean localEmbeddingsActive) {
            startupCheckInvocations++;
            lastLocalEmbeddingsActive = localEmbeddingsActive;
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus stop() {
            stopInvoked = true;
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus embeddingsDisabled() {
            embeddingsDisabledInvocations++;
            currentStatus = status(ManagedLocalOllamaState.DISABLED, Boolean.TRUE.equals(currentStatus.getOwned()));
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus pollOwnedProcess() {
            pollOwnedProcessInvocations++;
            if (pollOwnedProcessStatus != null) {
                currentStatus = pollOwnedProcessStatus;
            }
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus observeReadiness() {
            observeReadinessInvocations++;
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus pollExternalRuntime() {
            pollExternalRuntimeInvocations++;
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus attemptScheduledRetry() {
            attemptScheduledRetryInvocations++;
            return currentStatus;
        }

        @Override
        public ManagedLocalOllamaStatus currentStatus() {
            return currentStatus;
        }
    }

    private static final class NoopRuntimeProbePort implements OllamaRuntimeProbePort {

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
    }

    private static final class NoopProcessPort implements OllamaProcessPort {

        @Override
        public boolean isBinaryAvailable() {
            return false;
        }

        @Override
        public String getInstalledVersion() {
            return null;
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
}
