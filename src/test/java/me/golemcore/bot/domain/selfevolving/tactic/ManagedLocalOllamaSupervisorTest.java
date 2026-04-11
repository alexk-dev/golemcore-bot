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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.slf4j.LoggerFactory;

@SuppressWarnings({ "PMD.TestClassWithoutTestCases", "PMD.NullAssignment", "PMD.AvoidFieldNameMatchingMethodName",
        "PMD.UnusedPrivateMethod" })
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
        runtimeProbePort = new TestOllamaRuntimeProbePort(clock);
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
    void shouldNotProbeRuntimeVersionWhenSupervisorStartsDisabled() {
        TestManagedLocalOllamaSupervisor nullVersionSupervisor = new TestManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                ENDPOINT,
                null,
                SELECTED_MODEL);

        assertEquals(0, runtimeProbePort.versionChecks);

        ManagedLocalOllamaStatus status = nullVersionSupervisor.startupCheck(false);

        assertEquals(ManagedLocalOllamaState.DISABLED, status.getCurrentState());
        assertEquals(0, runtimeProbePort.versionChecks);
    }

    @Test
    void shouldCapStartupBudgetToFiveSecondsIncludingInitialExternalProbe() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.consumeReachabilityTimeout = true;
        processPort.binaryAvailable = true;

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, status.getCurrentState());
        assertEquals(Instant.parse("2026-04-02T00:00:05Z"), clock.instant());
        assertEquals(1, processPort.startCount);
    }

    @Test
    void shouldNotSleepPastRemainingStartupBudgetInsideWaitLoop() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.consumeReachabilityTimeouts.add(java.time.Duration.ofMillis(4500));
        processPort.binaryAvailable = true;

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, status.getCurrentState());
        assertEquals(Instant.parse("2026-04-02T00:00:05Z"), clock.instant());
        assertEquals(1, processPort.startCount);
    }

    @Test
    void shouldShareRemainingDiagnosticBudgetAcrossModelAndVersionChecks() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.consumeModelTimeouts.add(java.time.Duration.ofSeconds(3));
        runtimeProbePort.consumeVersionTimeouts.add(java.time.Duration.ofSeconds(3));
        processPort.binaryAvailable = true;

        TestManagedLocalOllamaSupervisor nullVersionSupervisor = new TestManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                ENDPOINT,
                null,
                SELECTED_MODEL);

        ManagedLocalOllamaStatus status = nullVersionSupervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.OWNED_READY, status.getCurrentState());
        assertEquals(Instant.parse("2026-04-02T00:00:05Z"), clock.instant());
        assertTrue(status.getModelPresent());
        assertEquals(VERSION, status.getVersion());
    }

    @Test
    void shouldStopOwnedProcessWhenManagedRuntimeIsDisabledAtRuntime() {
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
        assertFalse(disabledStatus.getOwned());
        assertEquals(1, processPort.stopCount);
    }

    @Test
    void shouldNotStopExternalRuntimeWhenEmbeddingsAreDisabledAtRuntime() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus startupStatus = supervisor.startupCheck(true);
        ManagedLocalOllamaStatus disabledStatus = supervisor.embeddingsDisabled();

        assertEquals(ManagedLocalOllamaState.EXTERNAL_READY, startupStatus.getCurrentState());
        assertEquals(ManagedLocalOllamaState.DISABLED, disabledStatus.getCurrentState());
        assertFalse(disabledStatus.getOwned());
        assertEquals(0, processPort.stopCount);
    }

    @Test
    void shouldRecoverFromExternalLostWhenRuntimeBecomesReachableAgain() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.runtimeVersion = VERSION;
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus ready = supervisor.startupCheck(true);
        assertEquals(ManagedLocalOllamaState.EXTERNAL_READY, ready.getCurrentState());

        runtimeProbePort.runtimeReachableResponses.add(false);
        ManagedLocalOllamaStatus lost = supervisor.pollExternalRuntime();
        assertEquals(ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST, lost.getCurrentState());

        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        ManagedLocalOllamaStatus recovered = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.EXTERNAL_READY, recovered.getCurrentState());
        assertFalse(recovered.getOwned());
    }

    @Test
    void shouldKeepRestartBackoffUntilRetryDeadlineIsReached() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;

        ManagedLocalOllamaStatus timeout = supervisor.startupCheck(true);
        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, timeout.getCurrentState());

        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;
        ManagedLocalOllamaStatus crashed = supervisor.pollOwnedProcess();
        assertEquals(ManagedLocalOllamaState.DEGRADED_CRASHED, crashed.getCurrentState());

        ManagedLocalOllamaStatus backoff = supervisor.attemptScheduledRetry();
        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, backoff.getCurrentState());

        ManagedLocalOllamaStatus stillBackoff = supervisor.attemptScheduledRetry();
        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, stillBackoff.getCurrentState());
        assertEquals(1, processPort.startCount);
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

    @Test
    void shouldRestartOwnedRuntimeWithExponentialBackoffAfterCrash() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;

        ManagedLocalOllamaStatus status = supervisor.pollOwnedProcess();

        assertEquals(ManagedLocalOllamaState.DEGRADED_CRASHED, status.getCurrentState());
        assertEquals(1, status.getRestartAttempts().intValue());
        assertNotNull(status.getNextRetryAt());
        assertEquals("2026-04-02T00:00:02Z", status.getNextRetryTime());
        assertTrue(status.getLastError().contains("137"));
    }

    @Test
    void shouldNotStartManagedRuntimeWhenExternalRuntimeIsLostLater() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);

        supervisor.startupCheck(true);
        runtimeProbePort.runtimeReachableResponses.add(false);

        ManagedLocalOllamaStatus status = supervisor.pollExternalRuntime();
        ManagedLocalOllamaStatus repeatedStartupStatus = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST, status.getCurrentState());
        assertFalse(status.getOwned());
        assertEquals(ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST, repeatedStartupStatus.getCurrentState());
        assertEquals(0, processPort.startCount);
    }

    @Test
    void shouldExposeOutdatedRuntimeWithoutChangingOwnership() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        TestManagedLocalOllamaSupervisor outdatedSupervisor = new TestManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                ENDPOINT,
                "0.18.0",
                SELECTED_MODEL);

        ManagedLocalOllamaStatus status = outdatedSupervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_OUTDATED, status.getCurrentState());
        assertTrue(status.getOwned());
    }

    @Test
    void shouldRemainDegradedAndIncrementAttemptsWhenRestartFails() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;
        supervisor.pollOwnedProcess();
        processPort.startFailure = new IllegalStateException("restart failed");
        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF,
                supervisor.attemptScheduledRetry().getCurrentState());
        clock.advanceSeconds(1);

        ManagedLocalOllamaStatus status = supervisor.attemptScheduledRetry();

        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, status.getCurrentState());
        assertEquals(2, status.getRestartAttempts().intValue());
        assertTrue(status.getLastError().contains("restart failed"));
    }

    @Test
    void shouldRestartOwnedRuntimeToReadyAfterBackoffDelay() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;
        supervisor.pollOwnedProcess();

        ManagedLocalOllamaStatus backoffStatus = supervisor.attemptScheduledRetry();
        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, backoffStatus.getCurrentState());

        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        clock.advanceSeconds(1);

        ManagedLocalOllamaStatus recoveredStatus = supervisor.attemptScheduledRetry();

        assertEquals(ManagedLocalOllamaState.OWNED_READY, recoveredStatus.getCurrentState());
        assertTrue(recoveredStatus.getOwned());
        assertEquals(2, processPort.startCount);
    }

    @Test
    void shouldNotBypassBackoffWhenStartupCheckIsCalledAfterCrash() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;
        supervisor.pollOwnedProcess();

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, status.getCurrentState());
        assertEquals(1, processPort.startCount);
    }

    @Test
    void shouldRetryWhenFirstRetryPollHappensAfterDeadline() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;
        supervisor.pollOwnedProcess();
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        clock.advanceSeconds(1);

        ManagedLocalOllamaStatus recoveredStatus = supervisor.attemptScheduledRetry();

        assertEquals(ManagedLocalOllamaState.OWNED_READY, recoveredStatus.getCurrentState());
        assertEquals(2, processPort.startCount);
    }

    @Test
    void shouldPreserveOwnedRuntimeOwnershipOnRepeatedStartupCheck() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        ManagedLocalOllamaStatus initialStatus = supervisor.startupCheck(true);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);

        ManagedLocalOllamaStatus repeatedStatus = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.OWNED_READY, initialStatus.getCurrentState());
        assertEquals(ManagedLocalOllamaState.OWNED_READY, repeatedStatus.getCurrentState());
        assertTrue(repeatedStatus.getOwned());
    }

    @Test
    void shouldNotDoubleStartOwnedRuntimeWhenStartupProbeTemporarilyFails() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        runtimeProbePort.runtimeReachableResponses.add(false);

        ManagedLocalOllamaStatus repeatedStatus = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.OWNED_READY, repeatedStatus.getCurrentState());
        assertTrue(repeatedStatus.getOwned());
        assertEquals(1, processPort.startCount);
    }

    @Test
    void shouldDetectCrashAfterStartupTimeoutWhenOwnedProcessDies() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;

        ManagedLocalOllamaStatus timeoutStatus = supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;

        ManagedLocalOllamaStatus crashedStatus = supervisor.pollOwnedProcess();

        assertEquals(ManagedLocalOllamaState.DEGRADED_START_TIMEOUT, timeoutStatus.getCurrentState());
        assertEquals(ManagedLocalOllamaState.DEGRADED_CRASHED, crashedStatus.getCurrentState());
        assertEquals(1, crashedStatus.getRestartAttempts().intValue());
    }

    @Test
    void shouldNotReprocessTheSameCrashOnRepeatedOwnedProcessPolls() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(false);
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);
        processPort.binaryAvailable = true;

        supervisor.startupCheck(true);
        processPort.ownedProcessAlive = false;
        processPort.ownedProcessExitCode = 137;

        ManagedLocalOllamaStatus firstCrashStatus = supervisor.pollOwnedProcess();
        ManagedLocalOllamaStatus secondCrashStatus = supervisor.pollOwnedProcess();

        assertEquals(ManagedLocalOllamaState.DEGRADED_CRASHED, firstCrashStatus.getCurrentState());
        assertEquals(ManagedLocalOllamaState.DEGRADED_CRASHED, secondCrashStatus.getCurrentState());
        assertEquals(1, secondCrashStatus.getRestartAttempts().intValue());
        assertEquals(firstCrashStatus.getNextRetryAt(), secondCrashStatus.getNextRetryAt());
    }

    @Test
    void shouldEnterRestartBackoffWhenInitialManagedStartFails() {
        runtimeProbePort.runtimeReachableResponses.add(false);
        processPort.binaryAvailable = true;
        processPort.startFailure = new IllegalStateException("spawn failed");

        ManagedLocalOllamaStatus status = supervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF, status.getCurrentState());
        assertEquals(1, status.getRestartAttempts().intValue());
        assertTrue(status.getLastError().contains("spawn failed"));
    }

    @Test
    void shouldNotTakeOwnershipWhenOutdatedExternalRuntimeDisappearsBeforePoll() {
        runtimeProbePort.runtimeReachableResponses.add(true);
        runtimeProbePort.modelResponses.add(true);

        TestManagedLocalOllamaSupervisor outdatedExternalSupervisor = new TestManagedLocalOllamaSupervisor(
                clock,
                runtimeProbePort,
                processPort,
                ENDPOINT,
                "0.18.0",
                SELECTED_MODEL);

        ManagedLocalOllamaStatus initialStatus = outdatedExternalSupervisor.startupCheck(true);
        runtimeProbePort.runtimeReachableResponses.add(false);

        ManagedLocalOllamaStatus followupStatus = outdatedExternalSupervisor.startupCheck(true);

        assertEquals(ManagedLocalOllamaState.DEGRADED_OUTDATED, initialStatus.getCurrentState());
        assertFalse(initialStatus.getOwned());
        assertEquals(ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST, followupStatus.getCurrentState());
        assertEquals(0, processPort.startCount);
    }

    @Test
    void shouldLogLifecycleEventsForExternalLossOutdatedAndStopping() {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            runtimeProbePort.runtimeReachableResponses.add(true);
            runtimeProbePort.modelResponses.add(true);

            supervisor.startupCheck(true);
            runtimeProbePort.runtimeReachableResponses.add(false);
            supervisor.pollExternalRuntime();

            TestManagedLocalOllamaSupervisor outdatedSupervisor = new TestManagedLocalOllamaSupervisor(
                    clock,
                    runtimeProbePort,
                    processPort,
                    ENDPOINT,
                    "0.18.0",
                    SELECTED_MODEL);
            runtimeProbePort.runtimeReachableResponses.add(false);
            runtimeProbePort.runtimeReachableResponses.add(false);
            runtimeProbePort.runtimeReachableResponses.add(true);
            runtimeProbePort.modelResponses.add(true);
            processPort.binaryAvailable = true;
            outdatedSupervisor.startupCheck(true);
            outdatedSupervisor.stop();

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            assertTrue(messages.stream().anyMatch(message -> message.contains("external ollama lost")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("ollama version 0.18.0 is outdated")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("stopping managed ollama")));
        } finally {
            ((Logger) LoggerFactory.getLogger(ManagedLocalOllamaSupervisor.class)).detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedLocalOllamaSupervisor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
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
        protected void pauseBeforeRetry(java.time.Duration delay) {
            observedStates.add(currentStatus().getCurrentState());
            ((MutableClock) getClock()).advance(delay);
        }
    }

    private static final class TestOllamaRuntimeProbePort implements OllamaRuntimeProbePort {

        private final MutableClock clock;
        private final Deque<Boolean> runtimeReachableResponses = new ArrayDeque<>();
        private final Deque<java.time.Duration> consumeReachabilityTimeouts = new ArrayDeque<>();
        private final Deque<java.time.Duration> consumeModelTimeouts = new ArrayDeque<>();
        private final Deque<java.time.Duration> consumeVersionTimeouts = new ArrayDeque<>();
        private final Deque<Boolean> modelResponses = new ArrayDeque<>();
        private final List<String> reachabilityEndpoints = new ArrayList<>();
        private int reachabilityChecks;
        private int versionChecks;
        private String runtimeVersion;
        private boolean consumeReachabilityTimeout;

        private TestOllamaRuntimeProbePort(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isRuntimeReachable(String endpoint, java.time.Duration timeout) {
            reachabilityChecks++;
            reachabilityEndpoints.add(endpoint);
            java.time.Duration requestedConsumption = consumeReachabilityTimeouts.isEmpty()
                    ? null
                    : consumeReachabilityTimeouts.removeFirst();
            if (requestedConsumption != null && timeout != null) {
                clock.advance(minDuration(requestedConsumption, timeout));
            } else if (consumeReachabilityTimeout && timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                clock.advance(timeout);
            }
            return runtimeReachableResponses.isEmpty() ? false : runtimeReachableResponses.removeFirst();
        }

        @Override
        public String getRuntimeVersion(String endpoint, java.time.Duration timeout) {
            versionChecks++;
            java.time.Duration requestedConsumption = consumeVersionTimeouts.isEmpty()
                    ? null
                    : consumeVersionTimeouts.removeFirst();
            if (requestedConsumption != null && timeout != null) {
                clock.advance(minDuration(requestedConsumption, timeout));
            }
            return runtimeVersion;
        }

        @Override
        public boolean hasModel(String endpoint, String model, java.time.Duration timeout) {
            java.time.Duration requestedConsumption = consumeModelTimeouts.isEmpty()
                    ? null
                    : consumeModelTimeouts.removeFirst();
            if (requestedConsumption != null && timeout != null) {
                clock.advance(minDuration(requestedConsumption, timeout));
            }
            return modelResponses.isEmpty() ? false : modelResponses.removeFirst();
        }

        private java.time.Duration minDuration(java.time.Duration left, java.time.Duration right) {
            return left.compareTo(right) <= 0 ? left : right;
        }
    }

    private static final class TestOllamaProcessPort implements OllamaProcessPort {

        private boolean binaryAvailable;
        private boolean ownedProcessAlive;
        private Integer ownedProcessExitCode;
        private int binaryAvailabilityChecks;
        private int startCount;
        private int stopCount;
        private RuntimeException startFailure;
        private final Deque<String> startEndpoints = new ArrayDeque<>();

        @Override
        public boolean isBinaryAvailable() {
            binaryAvailabilityChecks++;
            return binaryAvailable;
        }

        @Override
        public String getInstalledVersion() {
            return binaryAvailable ? VERSION : null;
        }

        @Override
        public void startServe(String endpoint) {
            if (startFailure != null) {
                throw startFailure;
            }
            startCount++;
            ownedProcessAlive = true;
            ownedProcessExitCode = null;
            startEndpoints.addLast(endpoint);
        }

        @Override
        public boolean isOwnedProcessAlive() {
            return ownedProcessAlive;
        }

        @Override
        public Integer getOwnedProcessExitCode() {
            return ownedProcessExitCode;
        }

        @Override
        public void stopOwnedProcess() {
            stopCount++;
            ownedProcessAlive = false;
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

        private void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
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
