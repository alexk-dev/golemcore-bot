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
    private static final String MISSING_BINARY_ERROR = "Ollama binary is missing";
    private static final String EXTERNAL_LOST_ERROR = "External Ollama runtime is no longer reachable";

    private final Clock clock;
    private final OllamaRuntimeProbePort runtimeProbePort;
    private final OllamaProcessPort processPort;
    private final String endpoint;
    private final String version;
    private final String selectedModel;
    private final Duration startupWindow;
    private final Duration initialRestartBackoff;
    private final String minimumSupportedVersion;

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
        this(clock, runtimeProbePort, processPort, endpoint, version, selectedModel, Duration.ofSeconds(5),
                Duration.ofSeconds(1), "0.19.0");
    }

    public ManagedLocalOllamaSupervisor(Clock clock,
            OllamaRuntimeProbePort runtimeProbePort,
            OllamaProcessPort processPort,
            String endpoint,
            String version,
            String selectedModel,
            Duration startupWindow,
            Duration initialRestartBackoff,
            String minimumSupportedVersion) {
        this.clock = clock;
        this.runtimeProbePort = runtimeProbePort;
        this.processPort = processPort;
        this.endpoint = endpoint;
        this.version = version;
        this.selectedModel = selectedModel;
        this.startupWindow = startupWindow != null ? startupWindow : Duration.ofSeconds(5);
        this.initialRestartBackoff = initialRestartBackoff != null ? initialRestartBackoff : Duration.ofSeconds(1);
        this.minimumSupportedVersion = minimumSupportedVersion != null && !minimumSupportedVersion.isBlank()
                ? minimumSupportedVersion
                : "0.19.0";
        this.currentStatus = buildStatus(
                ManagedLocalOllamaState.DISABLED,
                false,
                null,
                0,
                null,
                null,
                null,
                version,
                clock.instant());
    }

    public ManagedLocalOllamaStatus startupCheck(boolean localEmbeddingsActive) {
        Instant now = getClock().instant();
        Instant startupDeadline = now.plus(startupWindow);
        if (!localEmbeddingsActive) {
            return updateStatus(buildStatus(
                    ManagedLocalOllamaState.DISABLED,
                    currentStatus.getOwned(),
                    currentStatus.getLastError(),
                    safeRestartAttempts(),
                    currentStatus.getNextRetryAt(),
                    currentStatus.getNextRetryTime(),
                    currentStatus.getModelPresent(),
                    currentStatus.getVersion(),
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
            if (isRuntimeReachableWithin(startupDeadline)) {
                log.info("external ollama detected at {}", endpoint);
                return updateStatus(buildReadyStatusWithin(now, false, safeRestartAttempts(), startupDeadline));
            }
            return currentStatus;
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_OUTDATED
                && !Boolean.TRUE.equals(currentStatus.getOwned())) {
            if (isRuntimeReachableWithin(startupDeadline)) {
                log.info("external ollama detected at {}", endpoint);
                return updateStatus(buildReadyStatusWithin(now, false, safeRestartAttempts(), startupDeadline));
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
                    currentStatus.getVersion(),
                    now));
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF) {
            return attemptScheduledRetry();
        }
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT
                && Boolean.TRUE.equals(currentStatus.getOwned())) {
            if (isRuntimeReachableWithin(startupDeadline)) {
                return updateStatus(buildReadyStatusWithin(now, true, safeRestartAttempts(), startupDeadline));
            }
            return currentStatus;
        }

        if (isRuntimeReachableWithin(startupDeadline)) {
            boolean owned = Boolean.TRUE.equals(currentStatus.getOwned());
            if (!owned) {
                log.info("external ollama detected at {}", endpoint);
            }
            return updateStatus(buildReadyStatusWithin(now, owned, safeRestartAttempts(), startupDeadline));
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
                    currentStatus.getVersion(),
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
                currentStatus.getVersion(),
                now));
        try {
            processPort.startServe(endpoint);
            return updateStatus(waitForStartupResult(startupDeadline, 0));
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
                currentStatus.getVersion(),
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
                currentStatus.getVersion(),
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
                    currentStatus.getVersion(),
                    now));
            processPort.startServe(endpoint);
            ManagedLocalOllamaStatus startupResult = waitForStartupResult(now.plus(startupWindow), currentAttempts);
            if (startupResult.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT) {
                return updateStatus(
                        scheduleRestartBackoff(startTimeoutError(), currentAttempts + 1, getClock().instant()));
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
                currentStatus.getVersion(),
                now));
    }

    public ManagedLocalOllamaStatus currentStatus() {
        return currentStatus;
    }

    protected Clock getClock() {
        return clock;
    }

    protected void pauseBeforeRetry(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ManagedLocalOllamaStatus waitForStartupResult(Instant deadline, int restartAttempts) {
        while (getClock().instant().isBefore(deadline)) {
            if (isRuntimeReachableWithin(deadline)) {
                return buildReadyStatusWithin(getClock().instant(), true, restartAttempts, deadline);
            }
            Duration retryDelay = nextProbeDelay(deadline);
            if (retryDelay == null) {
                break;
            }
            pauseBeforeRetry(retryDelay);
        }

        if (isRuntimeReachableWithin(deadline)) {
            return buildReadyStatusWithin(getClock().instant(), true, restartAttempts, deadline);
        }

        log.warn("managed ollama start timed out after {} seconds", startupWindow.toSeconds());
        return buildStatus(
                ManagedLocalOllamaState.DEGRADED_START_TIMEOUT,
                true,
                startTimeoutError(),
                restartAttempts,
                null,
                null,
                false,
                currentStatus.getVersion(),
                getClock().instant());
    }

    private ManagedLocalOllamaStatus buildReadyStatus(Instant now, boolean owned, int restartAttempts) {
        return buildReadyStatusWithin(now, owned, restartAttempts, now.plus(startupWindow));
    }

    private ManagedLocalOllamaStatus buildReadyStatus(Instant now, boolean owned) {
        return buildReadyStatus(now, owned, safeRestartAttempts());
    }

    private ManagedLocalOllamaStatus buildReadyStatusWithin(Instant now,
            boolean owned,
            int restartAttempts,
            Instant deadline) {
        Duration remaining = remainingUntil(deadline);
        Boolean modelPresent = currentStatus.getModelPresent();
        String resolvedVersion = currentStatus.getVersion();
        Duration modelTimeout = remaining;
        if (modelTimeout != null && selectedModel != null) {
            modelPresent = runtimeProbePort.hasModel(endpoint, selectedModel, modelTimeout);
        }
        Duration versionTimeout = remainingUntil(deadline);
        if (versionTimeout != null) {
            resolvedVersion = resolveVersionWithin(versionTimeout);
        }
        if (isOutdatedVersion(resolvedVersion)) {
            log.warn("ollama version {} is outdated; minimum supported {}", resolvedVersion, minimumSupportedVersion);
            return buildStatus(
                    ManagedLocalOllamaState.DEGRADED_OUTDATED,
                    owned,
                    "Ollama runtime version " + resolvedVersion + " is older than minimum supported "
                            + minimumSupportedVersion,
                    restartAttempts,
                    null,
                    null,
                    modelPresent,
                    resolvedVersion,
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
                resolvedVersion,
                now);
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
                currentStatus.getVersion(),
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
                currentStatus.getVersion(),
                now);
    }

    private Duration calculateRestartBackoff(int restartAttempts) {
        int exponent = Math.max(0, restartAttempts - 1);
        long multiplier = 1L << Math.min(exponent, 10);
        return initialRestartBackoff.multipliedBy(multiplier);
    }

    private ManagedLocalOllamaStatus buildStatus(ManagedLocalOllamaState currentState,
            Boolean owned,
            String lastError,
            Integer restartAttempts,
            Instant nextRetryAt,
            String nextRetryTime,
            Boolean modelPresent,
            String resolvedVersion,
            Instant updatedAt) {
        return ManagedLocalOllamaStatus.builder()
                .currentState(currentState)
                .owned(owned)
                .endpoint(endpoint)
                .version(resolvedVersion)
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

    private String resolveVersionWithin(Duration timeout) {
        if (version != null) {
            return version;
        }
        if (timeout == null) {
            return null;
        }
        try {
            return runtimeProbePort.getRuntimeVersion(endpoint, timeout);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean isOutdatedVersion(String currentVersion) {
        if (currentVersion == null) {
            return false;
        }
        return compareVersions(currentVersion, minimumSupportedVersion) < 0;
    }

    private String startTimeoutError() {
        return "Ollama did not become healthy within " + startupWindow.toSeconds() + " seconds";
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

    private boolean isRuntimeReachableWithin(Instant deadline) {
        Duration remaining = remainingUntil(deadline);
        if (remaining == null) {
            return false;
        }
        return runtimeProbePort.isRuntimeReachable(endpoint, remaining);
    }

    private Duration remainingUntil(Instant deadline) {
        Instant now = getClock().instant();
        if (deadline == null || !now.isBefore(deadline)) {
            return null;
        }
        Duration remaining = Duration.between(now, deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return null;
        }
        return remaining;
    }

    private Duration nextProbeDelay(Instant deadline) {
        Duration remaining = remainingUntil(deadline);
        if (remaining == null) {
            return null;
        }
        Duration probeInterval = Duration.ofSeconds(1);
        return remaining.compareTo(probeInterval) < 0 ? remaining : probeInterval;
    }
}
