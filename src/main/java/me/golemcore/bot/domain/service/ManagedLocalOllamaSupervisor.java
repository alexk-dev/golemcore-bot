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

/**
 * Coordinates startup and lifecycle observation of a managed local Ollama
 * runtime.
 */
public class ManagedLocalOllamaSupervisor {

    private static final Duration STARTUP_WINDOW = Duration.ofSeconds(5);
    private static final String MISSING_BINARY_ERROR = "Ollama binary is missing";
    private static final String START_TIMEOUT_ERROR = "Ollama did not become healthy within 5 seconds";

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
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT
                && Boolean.TRUE.equals(currentStatus.getOwned())) {
            if (runtimeProbePort.isRuntimeReachable(endpoint)) {
                return updateStatus(buildReadyStatus(now, true));
            }
            return currentStatus;
        }

        if (runtimeProbePort.isRuntimeReachable(endpoint)) {
            return updateStatus(buildReadyStatus(now, false));
        }

        if (!processPort.isBinaryAvailable()) {
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

        updateStatus(buildStatus(
                ManagedLocalOllamaState.OWNED_STARTING,
                true,
                null,
                0,
                null,
                null,
                false,
                now));
        processPort.startServe(endpoint);
        return updateStatus(waitForStartupResult(now));
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

    public ManagedLocalOllamaStatus stop() {
        Instant now = getClock().instant();
        if (currentStatus.getCurrentState() == ManagedLocalOllamaState.STOPPING) {
            return currentStatus;
        }
        if (!Boolean.TRUE.equals(currentStatus.getOwned())) {
            return currentStatus;
        }
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

    private ManagedLocalOllamaStatus waitForStartupResult(Instant startedAt) {
        Instant deadline = startedAt.plus(STARTUP_WINDOW);
        while (getClock().instant().isBefore(deadline)) {
            if (runtimeProbePort.isRuntimeReachable(endpoint)) {
                return buildReadyStatus(getClock().instant(), true, 0);
            }
            pauseBeforeRetry();
        }

        if (runtimeProbePort.isRuntimeReachable(endpoint)) {
            return buildReadyStatus(getClock().instant(), true, 0);
        }

        return buildStatus(
                ManagedLocalOllamaState.DEGRADED_START_TIMEOUT,
                true,
                START_TIMEOUT_ERROR,
                0,
                null,
                null,
                false,
                getClock().instant());
    }

    private ManagedLocalOllamaStatus buildReadyStatus(Instant now, boolean owned, int restartAttempts) {
        boolean modelPresent = selectedModel != null && runtimeProbePort.hasModel(endpoint, selectedModel);
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
                .version(version != null ? version : runtimeProbePort.getRuntimeVersion(endpoint))
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
}
