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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates startup and lifecycle observation of a managed local Ollama
 * runtime.
 */
public class ManagedLocalOllamaSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ManagedLocalOllamaSupervisor.class);
    private static final Duration STARTUP_WINDOW = Duration.ofSeconds(5);
    private static final Duration INITIAL_RESTART_BACKOFF = Duration.ofSeconds(1);
    private static final String MINIMUM_SUPPORTED_VERSION = "0.19.0";
    private static final String MISSING_BINARY_ERROR = "Ollama binary is missing";
    private static final String START_TIMEOUT_ERROR = "Ollama did not become healthy within 5 seconds";
    private static final String EXTERNAL_LOST_ERROR = "External Ollama runtime is no longer reachable";

    private final Clock clock;
    private final OllamaRuntimeProbePort runtimeProbePort;
    private final OllamaProcessPort processPort;
    private final String endpoint;
    private final String version;
    private final String selectedModel;

    private ManagedLocalOllamaStatus currentStatus;

    public ManagedLocalOllamaSupervisor(Clock clock,
            OllamaRuntimeProbePort runtimeProbePort,
            OllamaProcessPort processPort) {
        this(clock, runtimeProbePort, processPort, null, null, null);
    }

    public ManagedLocalOllamaSupervisor(Clock clock,
            OllamaRuntimeProbePort runtimeProbePort,
            OllamaProcessPort processPort,
            String endpoint,
            String version,
            String selectedModel) {
        this.clock = clock;
        this.runtimeProbePort = runtimeProbePort;
        this.processPort = processPort;
        this.endpoint = endpoint;
        this.version = version;
        this.selectedModel = selectedModel;
        this.currentStatus = buildStatus(
                ManagedLocalOllamaState.DISABLED,
                false,
                null,
                0,
                null,
                null,
                null,
                clock.instant());
    }

    public ManagedLocalOllamaStatus startupCheck(boolean localEmbeddingsActive) {
        Instant now = getClock().instant();
        if (!localEmbeddingsActive) {
            return updateStatus(buildStatus(
                    ManagedLocalOllamaState.DISABLED,
                    currentStatus.getOwned(),
                    currentStatus.getLastError(),
                    safeRestartAttempts(),
                    currentStatus.getNextRetryAt(),
                    currentStatus.getNextRetryTime(),
                    currentStatus.getModelPresent(),
                    now));
        }

        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.STOPPING) {
            return currentStatus;
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_CRASHED
                && Boolean.TRUE.equals(currentStatus.getOwned())) {
            return attemptScheduledRetry();
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST
                && !Boolean.TRUE.equals(currentStatus.getOwned())) {
            if (runtimeProbePort.isRuntimeReachable(endpoint)) {
                log.info("external ollama detected at {}", endpoint);
                return updateStatus(buildReadyStatus(now, false));
            }
            return currentStatus;
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_OUTDATED
                && !Boolean.TRUE.equals(currentStatus.getOwned())) {
            if (runtimeProbePort.isRuntimeReachable(endpoint)) {
                log.info("external ollama detected at {}", endpoint);
                return updateStatus(buildReadyStatus(now, false));
            }
            log.warn("external ollama lost at {}", endpoint);
            return updateStatus(buildStatus(
                    ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST,
                    false,
                    EXTERNAL_LOST_ERROR,
                    0,
                    null,
                    null,
                    false,
                    now));
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF) {
            return attemptScheduledRetry();
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT
                && Boolean.TRUE.equals(currentStatus.getOwned())) {
            if (runtimeProbePort.isRuntimeReachable(endpoint)) {
                return updateStatus(buildReadyStatus(now, true));
            }
            return currentStatus;
        }

        if (runtimeProbePort.isRuntimeReachable(endpoint)) {
            boolean owned = Boolean.TRUE.equals(currentStatus.getOwned());
            if (!owned) {
                log.info("external ollama detected at {}", endpoint);
            }
            return updateStatus(buildReadyStatus(now, owned));
        }
        if (Boolean.TRUE.equals(currentStatus.getOwned())
                && (currentStatus.getCurrentState() == ManagedLocalOllamaState.OWNED_READY
                        || currentStatus.getCurrentState() == ManagedLocalOllamaState.OWNED_STARTING
                        || currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT
                        || currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_OUTDATED)) {
            if (processPort.isOwnedProcessAlive()) {
                return currentStatus;
            }
            return pollOwnedProcess();
        }

        if (!processPort.isBinaryAvailable()) {
            log.warn("ollama binary not found for managed local runtime at {}", endpoint);
            return updateStatus(buildStatus(
                    ManagedLocalOllamaState.DEGRADED_MISSING_BINARY,
                    false,
                    MISSING_BINARY_ERROR,
                    safeRestartAttempts(),
                    null,
                    null,
                    false,
                    now));
        }

        log.info("starting managed ollama at {}", endpoint);
        updateStatus(buildStatus(
                ManagedLocalOllamaState.OWNED_STARTING,
                true,
                null,
                0,
                null,
                null,
                false,
                now));
        try {
            processPort.startServe(endpoint);
            return updateStatus(waitForStartupResult(now, 0));
        } catch (RuntimeException exception) {
            String lastError = "Managed Ollama start failed: " + exception.getMessage();
            log.warn("managed ollama start failed: {}", exception.getMessage());
            return updateStatus(scheduleRestartBackoff(lastError, 1, now));
        }
    }

    public ManagedLocalOllamaStatus startupCheck() {
        return startupCheck(true);
    }

    public ManagedLocalOllamaStatus observeReadiness() {
        Instant now = getClock().instant();
        ManagedLocalOllamaState state = currentStatus.getCurrentState();
        if (state == ManagedLocalOllamaState.DISABLED || state == ManagedLocalOllamaState.STOPPING) {
            return currentStatus;
        }

        if (runtimeProbePort.isRuntimeReachable(endpoint)) {
            return updateStatus(buildReadyStatus(now, Boolean.TRUE.equals(currentStatus.getOwned())));
        }

        if (state == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT) {
            return currentStatus;
        }

        return currentStatus;
    }

    public ManagedLocalOllamaStatus embeddingsDisabled() {
        Instant now = getClock().instant();
        return updateStatus(buildStatus(
                ManagedLocalOllamaState.DISABLED,
                currentStatus.getOwned(),
                currentStatus.getLastError(),
                safeRestartAttempts(),
                currentStatus.getNextRetryAt(),
                currentStatus.getNextRetryTime(),
                currentStatus.getModelPresent(),
                now));
    }

    public ManagedLocalOllamaStatus pollOwnedProcess() {
        if (!Boolean.TRUE.equals(currentStatus.getOwned())) {
            return currentStatus;
        }
        ManagedLocalOllamaState state = currentStatus.getCurrentState();
        if (state == ManagedLocalOllamaState.DISABLED
                || state == ManagedLocalOllamaState.STOPPING
                || state == ManagedLocalOllamaState.DEGRADED_CRASHED
                || state == ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF) {
            return currentStatus;
        }
        if (processPort.isOwnedProcessAlive()) {
            return currentStatus;
        }

        Instant now = getClock().instant();
        Integer exitCode = processPort.getOwnedProcessExitCode();
        String lastError = exitCode != null
                ? "Managed Ollama exited with code " + exitCode
                : "Managed Ollama exited unexpectedly";
        log.warn("managed ollama crashed: {}", lastError);
        return updateStatus(scheduleCrash(lastError, safeRestartAttempts() + 1, now));
    }

    public ManagedLocalOllamaStatus pollExternalRuntime() {
        if (Boolean.TRUE.equals(currentStatus.getOwned())) {
            return currentStatus;
        }
        ManagedLocalOllamaState state = currentStatus.getCurrentState();
        if (state != ManagedLocalOllamaState.EXTERNAL_READY
                && state != ManagedLocalOllamaState.DEGRADED_OUTDATED) {
            return currentStatus;
        }
        if (runtimeProbePort.isRuntimeReachable(endpoint)) {
            return updateStatus(buildReadyStatus(getClock().instant(), false));
        }
        log.warn("external ollama lost at {}", endpoint);
        return updateStatus(buildStatus(
                ManagedLocalOllamaState.DEGRADED_EXTERNAL_LOST,
                false,
                EXTERNAL_LOST_ERROR,
                0,
                null,
                null,
                false,
                getClock().instant()));
    }

    public ManagedLocalOllamaStatus attemptScheduledRetry() {
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_CRASHED) {
            if (currentStatus.getNextRetryAt() == null) {
                return currentStatus;
            }
            updateStatus(currentStatus.toBuilder()
                    .currentState(ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF)
                    .updatedAt(getClock().instant())
                    .build());
        }

        if (currentStatus.getCurrentState() != ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF
                || !Boolean.TRUE.equals(currentStatus.getOwned())
                || currentStatus.getNextRetryAt() == null) {
            return currentStatus;
        }

        Instant now = getClock().instant();
        if (now.isBefore(currentStatus.getNextRetryAt())) {
            return currentStatus;
        }

        int currentAttempts = safeRestartAttempts();
        try {
            log.info("managed ollama restart requested");
            updateStatus(buildStatus(
                    ManagedLocalOllamaState.OWNED_STARTING,
                    true,
                    null,
                    currentAttempts,
                    null,
                    null,
                    false,
                    now));
            processPort.startServe(endpoint);
            ManagedLocalOllamaStatus startupResult = waitForStartupResult(now, currentAttempts);
            if (startupResult.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT) {
                return updateStatus(
                        scheduleRestartBackoff(START_TIMEOUT_ERROR, currentAttempts + 1, getClock().instant()));
            }
            log.info("managed ollama restart succeeded");
            return updateStatus(startupResult);
        } catch (RuntimeException exception) {
            String lastError = "Managed Ollama restart failed: " + exception.getMessage();
            log.warn("managed ollama restart failed: {}", exception.getMessage());
            return updateStatus(scheduleRestartBackoff(lastError, currentAttempts + 1, now));
        }
    }

    public ManagedLocalOllamaStatus stop() {
        Instant now = getClock().instant();
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.STOPPING) {
            return currentStatus;
        }
        if (!Boolean.TRUE.equals(currentStatus.getOwned())) {
            return currentStatus;
        }
        log.info("stopping managed ollama at {}", endpoint);
        if (processPort.isOwnedProcessAlive()) {
            processPort.stopOwnedProcess();
        }
        return updateStatus(buildStatus(
                ManagedLocalOllamaState.STOPPING,
                true,
                currentStatus.getLastError(),
                safeRestartAttempts(),
                currentStatus.getNextRetryAt(),
                currentStatus.getNextRetryTime(),
                currentStatus.getModelPresent(),
                now));
    }

    public ManagedLocalOllamaStatus currentStatus() {
        return currentStatus;
    }

    protected Clock getClock() {
        return clock;
    }

    protected void pauseBeforeRetry() {
        try {
            Thread.sleep(Duration.ofSeconds(1).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ManagedLocalOllamaStatus waitForStartupResult(Instant startedAt, int restartAttempts) {
        Instant deadline = startedAt.plus(STARTUP_WINDOW);
        while (getClock().instant().isBefore(deadline)) {
            if (runtimeProbePort.isRuntimeReachable(endpoint)) {
                return buildReadyStatus(getClock().instant(), true, restartAttempts);
            }
            pauseBeforeRetry();
        }

        if (runtimeProbePort.isRuntimeReachable(endpoint)) {
            return buildReadyStatus(getClock().instant(), true, restartAttempts);
        }

        log.warn("managed ollama start timed out after {} seconds", STARTUP_WINDOW.toSeconds());
        return buildStatus(
                ManagedLocalOllamaState.DEGRADED_START_TIMEOUT,
                true,
                START_TIMEOUT_ERROR,
                restartAttempts,
                null,
                null,
                false,
                getClock().instant());
    }

    private ManagedLocalOllamaStatus buildReadyStatus(Instant now, boolean owned, int restartAttempts) {
        boolean modelPresent = selectedModel != null && runtimeProbePort.hasModel(endpoint, selectedModel);
        String resolvedVersion = resolveVersion();
        if (isOutdatedVersion(resolvedVersion)) {
            log.warn("ollama version {} is outdated; minimum supported {}", resolvedVersion, MINIMUM_SUPPORTED_VERSION);
            return buildStatus(
                    ManagedLocalOllamaState.DEGRADED_OUTDATED,
                    owned,
                    "Ollama runtime version " + resolvedVersion + " is older than minimum supported "
                            + MINIMUM_SUPPORTED_VERSION,
                    restartAttempts,
                    null,
                    null,
                    modelPresent,
                    now);
        }
        if (owned) {
            log.info("managed ollama ready at {}", endpoint);
        }
        return buildStatus(
                owned ? ManagedLocalOllamaState.OWNED_READY : ManagedLocalOllamaState.EXTERNAL_READY,
                owned,
                null,
                restartAttempts,
                null,
                null,
                modelPresent,
                now);
    }

    private ManagedLocalOllamaStatus buildReadyStatus(Instant now, boolean owned) {
        return buildReadyStatus(now, owned, safeRestartAttempts());
    }

    private ManagedLocalOllamaStatus scheduleCrash(String lastError, int restartAttempts, Instant now) {
        Duration delay = calculateRestartBackoff(restartAttempts);
        Instant nextRetryAt = now.plus(delay);
        log.warn("managed ollama restart scheduled in {}s", delay.toSeconds());
        return buildStatus(
                ManagedLocalOllamaState.DEGRADED_CRASHED,
                true,
                lastError,
                restartAttempts,
                nextRetryAt,
                nextRetryAt.toString(),
                false,
                now);
    }

    private ManagedLocalOllamaStatus scheduleRestartBackoff(String lastError, int restartAttempts, Instant now) {
        Duration delay = calculateRestartBackoff(restartAttempts);
        Instant nextRetryAt = now.plus(delay);
        log.warn("managed ollama restart scheduled in {}s", delay.toSeconds());
        return buildStatus(
                ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF,
                true,
                lastError,
                restartAttempts,
                nextRetryAt,
                nextRetryAt.toString(),
                false,
                now);
    }

    private Duration calculateRestartBackoff(int restartAttempts) {
        int exponent = Math.max(0, restartAttempts - 1);
        long multiplier = 1L << Math.min(exponent, 10);
        return INITIAL_RESTART_BACKOFF.multipliedBy(multiplier);
    }

    private ManagedLocalOllamaStatus buildStatus(ManagedLocalOllamaState currentState,
            Boolean owned,
            String lastError,
            Integer restartAttempts,
            Instant nextRetryAt,
            String nextRetryTime,
            Boolean modelPresent,
            Instant updatedAt) {
        return ManagedLocalOllamaStatus.builder()
                .currentState(currentState)
                .owned(owned)
                .endpoint(endpoint)
                .version(resolveVersion())
                .selectedModel(selectedModel)
                .modelPresent(modelPresent)
                .lastError(lastError)
                .restartAttempts(restartAttempts)
                .nextRetryAt(nextRetryAt)
                .nextRetryTime(nextRetryTime)
                .updatedAt(updatedAt)
                .build();
    }

    private ManagedLocalOllamaStatus updateStatus(ManagedLocalOllamaStatus status) {
        currentStatus = status;
        return currentStatus;
    }

    private Integer safeRestartAttempts() {
        return currentStatus.getRestartAttempts() != null ? currentStatus.getRestartAttempts() : 0;
    }

    private String resolveVersion() {
        if (version != null) {
            return version;
        }
        try {
            return runtimeProbePort.getRuntimeVersion(endpoint);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isOutdatedVersion(String currentVersion) {
        if (currentVersion == null) {
            return false;
        }
        return compareVersions(currentVersion, MINIMUM_SUPPORTED_VERSION) < 0;
    }

    private int compareVersions(String left, String right) {
        int[] leftParts = parseVersion(left);
        int[] rightParts = parseVersion(right);
        for (int index = 0; index < 3; index++) {
            if (leftParts[index] != rightParts[index]) {
                return Integer.compare(leftParts[index], rightParts[index]);
            }
        }
        return 0;
    }

    private int[] parseVersion(String value) {
        String normalized = value.startsWith("v") ? value.substring(1) : value;
        String[] tokens = normalized.split("[^0-9]+");
        int[] parts = new int[] { 0, 0, 0 };
        int partIndex = 0;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            parts[partIndex++] = Integer.parseInt(token);
            if (partIndex == parts.length) {
                break;
            }
        }
        return parts;
    }
}
