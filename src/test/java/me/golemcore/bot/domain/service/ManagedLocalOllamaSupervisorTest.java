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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedLocalOllamaSupervisorTest {

    private static final String ENDPOINT = "http://127.0.0.1:11434";
    private static final String VERSION = "0.19.0";
    private static final String SELECTED_MODEL = "qwen3-embedding:0.6b";

    private MutableClock clock;
    private TestOllamaRuntimeProbePort runtimeProbePort;
    private TestOllamaProcessPort processPort;
    private TestManagedLocalOllamaSupervisor supervisor;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-04-02T00:00:00Z"), ZoneId.of("UTC"));
        runtimeProbePort = new TestOllamaRuntimeProbePort();
        processPort = new TestOllamaProcessPort();
        supervisor = new TestManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                ENDPOINT,
                VERSION,
                SELECTED_MODEL);
    }

    @Test
    void shouldReturnExternalReadyWhenRuntimeIsReachable() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.EXTERNAL_READY, status.getCurrentState());
        assertFalse(status.getOwned());
        assertEquals(ENDPOINT, status.getEndpoint());
        assertEquals(VERSION, status.getVersion());
        assertEquals(SELECTED_MODEL, status.getSelectedModel());
        assertTrue(status.getModelPresent());
        assertEquals(1, runtimeProbePort.reachabilityChecks);
        assertEquals(List.of(ENDPOINT), runtimeProbePort.reachabilityEndpoints);
        assertEquals(0, processPort.binaryAvailabilityChecks);
        assertEquals(0, processPort.startCount);
    }

    @Test
    void shouldReportMissingModelWhenReachableRuntimeDoesNotHaveSelectedModel() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(false);

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.EXTERNAL_READY, status.getCurrentState());
        assertFalse(status.getModelPresent());
    }

    @Test
    void shouldReturnOwnedReadyWhenRuntimeStartsWithinFiveSeconds() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        processPort.binaryAvailable = true;
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.OWNED_READY, status.getCurrentState());
        assertTrue(status.getOwned());
        assertEquals(0, status.getRestartAttempts().intValue());
        assertEquals(1, processPort.startCount);
        assertEquals(1, processPort.startEndpoints.size());
        assertEquals(ENDPOINT, processPort.startEndpoints.peekFirst());
        assertEquals(3, runtimeProbePort.reachabilityChecks);
        assertTrue(supervisor.observedStates.contains(ManagedLocalOllamaState.OWNED_STARTING));
    }

    @Test
    void shouldReturnDegradedStartTimeoutAfterFiveSeconds() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;
        runtimeProbePort.runtimeVersion = VERSION;

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, status.getCurrentState());
        assertTrue(status.getOwned());
        assertEquals(0, status.getRestartAttempts().intValue());
        assertEquals(Instant.parse("2026-04-02T00:00:05Z"), clock.instant());
        assertNull(status.getNextRetryAt());
        assertNull(status.getNextRetryTime());
        assertEquals(ENDPOINT, status.getEndpoint());
        assertEquals(VERSION, status.getVersion());
        assertEquals(SELECTED_MODEL, status.getSelectedModel());
        assertTrue(supervisor.observedStates.contains(ManagedLocalOllamaState.OWNED_STARTING));
    }

    @Test
    void shouldReturnDegradedMissingBinaryWhenNoRuntimeAndNoBinaryExist() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = false;

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_MISSING_BINARY, status.getCurrentState());
        assertFalse(status.getOwned());
        assertEquals(1, runtimeProbePort.reachabilityChecks);
        assertEquals(1, processPort.binaryAvailabilityChecks);
        assertEquals("Ollama binary is missing", status.getLastError());
    }

    @Test
    void shouldReturnDisabledWhenLocalEmbeddingsAreInactive() {
        ManagedLocalOllamaStatus status = supervisor.startupCheck(false);

        assertEquals(ManagedLocalOllamaState.DISABLED, status.getCurrentState());
        assertFalse(status.getOwned());
        assertEquals(0, runtimeProbePort.reachabilityChecks);
        assertEquals(0, processPort.binaryAvailabilityChecks);
        assertEquals(0, processPort.startCount);
        assertEquals(0, processPort.stopCount);
    }

    @Test
    void shouldNotStopOwnedProcessWhenEmbeddingsAreDisabledAtRuntime() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;

        ManagedLocalOllamaStatus startupStatus = supervisor.startupCheck(true);
        ManagedLocalOllamaStatus disabledStatus = supervisor.startupCheck(false);

        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, startupStatus.getCurrentState());
        assertEquals(ManagedLocalOllamaState.DISABLED, disabledStatus.getCurrentState());
        assertTrue(disabledStatus.getOwned());
        assertEquals(0, processPort.stopCount);
    }

    @Test
    void shouldStopOnlyOwnedProcessOnShutdown() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        ManagedLocalOllamaStatus stoppingStatus = supervisor.stop();

        assertEquals(ManagedLocalOllamaState.STOPPING, stoppingStatus.getCurrentState());
        assertEquals(1, processPort.stopCount);
    }

    @Test
    void shouldStopOwnedProcessOnlyOnceOnRepeatedShutdown() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);

        ManagedLocalOllamaStatus firstStopStatus = supervisor.stop();
        ManagedLocalOllamaStatus secondStopStatus = supervisor.stop();

        assertEquals(ManagedLocalOllamaState.STOPPING, firstStopStatus.getCurrentState());
        assertEquals(ManagedLocalOllamaState.STOPPING, secondStopStatus.getCurrentState());
        assertEquals(1, processPort.stopCount);
    }

    @Test
    void shouldNeverStopExternalRuntimeOnShutdown() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.runtimeVersion = VERSION;

        supervisor.startupCheck(true);
        ManagedLocalOllamaStatus stoppingStatus = supervisor.stop();

        assertEquals(ManagedLocalOllamaState.EXTERNAL_READY, stoppingStatus.getCurrentState());
        assertEquals(0, processPort.stopCount);
    }

    @Test
    void shouldRecoverFromStartTimeoutThroughReadinessObservation() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus degradedStatus = supervisor.startupCheck(true);
        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, degradedStatus.getCurrentState());

        runtimeProbePort.runtimeReachableResponses.add(true);
        ManagedLocalOllamaStatus recoveredStatus = supervisor.observeReadiness();

        assertEquals(ManagedLocalOllamaState.OWNED_READY, recoveredStatus.getCurrentState());
        assertTrue(recoveredStatus.getOwned());
    }

    @Test
    void shouldRecoverOnRepeatedStartupCheckWithoutRestartingProcess() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus degradedStatus = supervisor.startupCheck(true);
        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, degradedStatus.getCurrentState());
        assertEquals(1, processPort.startCount);

        runtimeProbePort.runtimeReachableResponses.add(true);
        ManagedLocalOllamaStatus recoveredStatus = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.OWNED_READY, recoveredStatus.getCurrentState());
        assertEquals(1, processPort.startCount);
    }

    private static final class TestManagedLocalOllamaSupervisor extends ManagedLocalOllamaSupervisor {

        private final List<ManagedLocalOllamaState> observedStates = new ArrayList<>();

        private TestManagedLocalOllamaSupervisor(Clock clock,
                OllamaRuntimeProbePort runtimeProbePort,
                OllamaProcessPort processPort,
                String endpoint,
                String version,
                String selectedModel) {
            super(clock, runtimeProbePort, processPort, endpoint, version, selectedModel);
        }

        @Override
        protected void pauseBeforeRetry() {
            observedStates.add(currentStatus().getCurrentState());
            ((MutableClock) getClock()).advanceSeconds(1);
        }
    }

    private static final class TestOllamaRuntimeProbePort implements OllamaRuntimeProbePort {

        private final Deque<Boolean> runtimeReachableResponses = new ArrayDeque<>();
        private final Deque<Boolean> modelResponses = new ArrayDeque<>();
        private final List<String> reachabilityEndpoints = new ArrayList<>();
        private int reachabilityChecks;
        private String runtimeVersion;

        @Override
        public boolean isRuntimeReachable(String endpoint) {
            reachabilityChecks++;
            reachabilityEndpoints.add(endpoint);
            return runtimeReachableResponses.isEmpty() ? false : runtimeReachableResponses.removeFirst();
        }

        @Override
        public String getRuntimeVersion(String endpoint) {
            return runtimeVersion;
        }

        @Override
        public boolean hasModel(String endpoint, String model) {
            return modelResponses.isEmpty() ? false : modelResponses.removeFirst();
        }
    }

    private static final class TestOllamaProcessPort implements OllamaProcessPort {

        private boolean binaryAvailable;
        private int binaryAvailabilityChecks;
        private int startCount;
        private int stopCount;
        private final Deque<String> startEndpoints = new ArrayDeque<>();

        @Override
        public boolean isBinaryAvailable() {
            binaryAvailabilityChecks++;
            return binaryAvailable;
        }

        @Override
        public void startServe(String endpoint) {
            startCount++;
            startEndpoints.addLast(endpoint);
        }

        @Override
        public boolean isOwnedProcessAlive() {
            return startCount > stopCount;
        }

        @Override
        public void stopOwnedProcess() {
            stopCount++;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zoneId;

        private MutableClock(Instant instant, ZoneId zoneId) {
            this.instant = instant;
            this.zoneId = zoneId;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private MutableClock getClock() {
        return clock;
    }
}
