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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.selfevolving.tactic.ManagedLocalOllamaSupervisor;
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
    void shouldTreatMissingSelfEvolvingConfigAsInactiveManagedEndpoint() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(null);

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertFalse(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldTreatIpv6LoopbackBaseUrlAsLocalManagedEndpoint() {
        RuntimeConfig.SelfEvolvingConfig config = localEmbeddingsConfig(true);
        config.getTactics().getSearch().getEmbeddings().setBaseUrl("http://[::1]:11434");
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertTrue(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldFallbackToDefaultLifecycleSettingsWhenLocalConfigOverridesAreInvalid() {
        RuntimeConfig.SelfEvolvingConfig config = localEmbeddingsConfig(true);
        config.getTactics().getSearch().getEmbeddings().setBaseUrl("   ");
        config.getTactics().getSearch().getEmbeddings().getLocal().setStartupTimeoutMs(0);
        config.getTactics().getSearch().getEmbeddings().getLocal().setInitialRestartBackoffMs(0);
        config.getTactics().getSearch().getEmbeddings().getLocal().setMinimumRuntimeVersion("   ");
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);

        bridge.runStartupGate();

        assertEquals("http://127.0.0.1:11434", supervisor.lastRefreshEndpoint);
        assertEquals(Duration.ofSeconds(5), supervisor.lastRefreshStartupWindow);
        assertEquals(Duration.ofSeconds(1), supervisor.lastRefreshInitialRestartBackoff);
        assertNull(supervisor.lastRefreshMinimumSupportedVersion);
    }

    @Test
    void shouldRejectMalformedLocalBaseUrlForManagedEndpoints() {
        RuntimeConfig.SelfEvolvingConfig config = localEmbeddingsConfig(true);
        config.getTactics().getSearch().getEmbeddings().setBaseUrl("http:///missing-host");
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertFalse(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldTreatNonHybridSearchModeAsInactiveManagedEndpoint() {
        RuntimeConfig.SelfEvolvingConfig config = localEmbeddingsConfig(true);
        config.getTactics().getSearch().setMode("keyword");
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertFalse(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldTreatDisabledEmbeddingsAsInactiveManagedEndpoint() {
        RuntimeConfig.SelfEvolvingConfig config = localEmbeddingsConfig(true);
        config.getTactics().getSearch().getEmbeddings().setEnabled(false);
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertFalse(supervisor.lastLocalEmbeddingsActive);
    }

    @Test
    void shouldTreatNonOllamaProvidersAsInactiveManagedEndpoint() {
        RuntimeConfig.SelfEvolvingConfig config = localEmbeddingsConfig(true);
        config.getTactics().getSearch().getEmbeddings().setProvider("openai");
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(config);

        bridge.runStartupGate();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertFalse(supervisor.lastLocalEmbeddingsActive);
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
    void shouldRecheckStartupWhenStatusIsMissingDuringWatchdogTick() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = null;

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertTrue(supervisor.lastLocalEmbeddingsActive);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
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
    void shouldRecheckStartupForExternalStartTimeoutState() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, false);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.startupCheckInvocations);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
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
    void shouldMonitorOwnedOutdatedRuntimeAndRetryWhenPollSchedulesBackoff() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.DEGRADED_OUTDATED, true);
        supervisor.pollOwnedProcessStatus = status(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, true);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.pollOwnedProcessInvocations);
        assertEquals(1, supervisor.attemptScheduledRetryInvocations);
        assertEquals(0, supervisor.pollExternalRuntimeInvocations);
    }

    @Test
    void shouldPollExternalRuntimeForOutdatedExternalRuntime() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.DEGRADED_OUTDATED, false);

        bridge.runWatchdogTick();

        assertEquals(1, supervisor.pollExternalRuntimeInvocations);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
        assertEquals(0, supervisor.attemptScheduledRetryInvocations);
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

    @Test
    void shouldIgnoreStoppingStateDuringWatchdogTick() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.STOPPING, true);

        bridge.runWatchdogTick();

        assertEquals(0, supervisor.startupCheckInvocations);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
        assertEquals(0, supervisor.pollExternalRuntimeInvocations);
        assertEquals(0, supervisor.attemptScheduledRetryInvocations);
    }

    @Test
    void shouldIgnoreMissingBinaryStateDuringWatchdogTick() {
        when(runtimeConfigService.getSelfEvolvingConfig()).thenReturn(localEmbeddingsConfig(true));
        supervisor.currentStatus = status(ManagedLocalOllamaState.DEGRADED_MISSING_BINARY, false);

        bridge.runWatchdogTick();

        assertEquals(0, supervisor.startupCheckInvocations);
        assertEquals(0, supervisor.pollOwnedProcessInvocations);
        assertEquals(0, supervisor.pollExternalRuntimeInvocations);
        assertEquals(0, supervisor.attemptScheduledRetryInvocations);
    }

    @Test
    void shouldRestartManagedRuntimeWhenManagedEndpointChanges() {
        RuntimeConfigService liveRuntimeConfigService = mock(RuntimeConfigService.class);
        RuntimeConfig.SelfEvolvingConfig initialConfig = localEmbeddingsConfig(true);
        RuntimeConfig.SelfEvolvingConfig updatedConfig = localEmbeddingsConfig(true);
        updatedConfig.getTactics().getSearch().getEmbeddings().setBaseUrl("http://127.0.0.1:22434");
        when(liveRuntimeConfigService.getSelfEvolvingConfig()).thenReturn(initialConfig, updatedConfig, updatedConfig);

        BridgeMutableClock clock = new BridgeMutableClock(Instant.parse("2026-04-02T03:00:00Z"));
        BridgeRuntimeProbePort runtimeProbePort = new BridgeRuntimeProbePort(clock);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        runtimeProbePort.runtimeVersion = "0.19.0";

        BridgeProcessPort processPort = new BridgeProcessPort();
        processPort.binaryAvailable = true;

        ManagedLocalOllamaSupervisor liveSupervisor = new ManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                "http://127.0.0.1:11434",
                "0.19.0",
                "qwen3-embedding:0.6b");
        ManagedLocalOllamaLifecycleBridge liveBridge = new ManagedLocalOllamaLifecycleBridge(
                liveRuntimeConfigService,
                liveSupervisor);

        liveBridge.runStartupGate();
        runtimeProbePort.consumeReachabilityTimeouts.add(Duration.ofSeconds(5));
        liveBridge.runWatchdogTick();

        assertEquals(2, processPort.startEndpoints.size());
        assertEquals("http://127.0.0.1:11434", processPort.startEndpoints.removeFirst());
        assertEquals("http://127.0.0.1:22434", processPort.startEndpoints.removeFirst());
        assertEquals(1, processPort.stopCount);
        assertEquals("http://127.0.0.1:22434", liveSupervisor.currentStatus().getEndpoint());

        processPort.ownedProcessAlive = false;
        liveBridge.stop();
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
        private String lastRefreshEndpoint;
        private Duration lastRefreshStartupWindow;
        private Duration lastRefreshInitialRestartBackoff;
        private String lastRefreshMinimumSupportedVersion;
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
        public ManagedLocalOllamaStatus refreshConfiguration(
                String endpoint,
                String selectedModel,
                Duration startupWindow,
                Duration initialRestartBackoff,
                String minimumSupportedVersion) {
            lastRefreshEndpoint = endpoint;
            lastRefreshStartupWindow = startupWindow;
            lastRefreshInitialRestartBackoff = initialRestartBackoff;
            lastRefreshMinimumSupportedVersion = minimumSupportedVersion;
            return super.refreshConfiguration(
                    endpoint,
                    selectedModel,
                    startupWindow,
                    initialRestartBackoff,
                    minimumSupportedVersion);
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

    private static final class BridgeRuntimeProbePort implements OllamaRuntimeProbePort {

        private final BridgeMutableClock clock;
        private final Deque<Boolean> runtimeReachableResponses = new ArrayDeque<>();
        private final Deque<Duration> consumeReachabilityTimeouts = new ArrayDeque<>();
        private final Deque<Boolean> modelResponses = new ArrayDeque<>();
        private String runtimeVersion;

        private BridgeRuntimeProbePort(BridgeMutableClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isRuntimeReachable(String endpoint, Duration timeout) {
            Duration requestedConsumption = consumeReachabilityTimeouts.isEmpty() ? null
                    : consumeReachabilityTimeouts.removeFirst();
            if (requestedConsumption != null && timeout != null) {
                clock.advance(minDuration(requestedConsumption, timeout));
            }
            return runtimeReachableResponses.isEmpty() ? false : runtimeReachableResponses.removeFirst();
        }

        @Override
        public String getRuntimeVersion(String endpoint, Duration timeout) {
            return runtimeVersion;
        }

        @Override
        public boolean hasModel(String endpoint, String model, Duration timeout) {
            return modelResponses.isEmpty() ? false : modelResponses.removeFirst();
        }

        private Duration minDuration(Duration left, Duration right) {
            return left.compareTo(right) <= 0 ? left : right;
        }
    }

    private static final class BridgeProcessPort implements OllamaProcessPort {

        private boolean binaryAvailable;
        private boolean ownedProcessAlive;
        private int stopCount;
        private final Deque<String> startEndpoints = new ArrayDeque<>();

        @Override
        public boolean isBinaryAvailable() {
            return binaryAvailable;
        }

        @Override
        public String getInstalledVersion() {
            return binaryAvailable ? "0.19.0" : null;
        }

        @Override
        public void startServe(String endpoint) {
            ownedProcessAlive = true;
            startEndpoints.addLast(endpoint);
        }

        @Override
        public boolean isOwnedProcessAlive() {
            return ownedProcessAlive;
        }

        @Override
        public Integer getOwnedProcessExitCode() {
            return null;
        }

        @Override
        public void stopOwnedProcess() {
            stopCount++;
            ownedProcessAlive = false;
        }
    }

    private static final class BridgeMutableClock extends Clock {

        private Instant instant;

        private BridgeMutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
