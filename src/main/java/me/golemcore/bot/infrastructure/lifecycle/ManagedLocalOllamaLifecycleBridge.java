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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.service.ManagedLocalOllamaSupervisor;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework-facing lifecycle bridge for the managed local Ollama supervisor.
 */
public class ManagedLocalOllamaLifecycleBridge {

    private static final Logger log = LoggerFactory.getLogger(ManagedLocalOllamaLifecycleBridge.class);
    private static final long WATCHDOG_INTERVAL_SECONDS = 1L;

    private final RuntimeConfigService runtimeConfigService;
    private final ManagedLocalOllamaSupervisor supervisor;
    private volatile boolean started;
    private ScheduledExecutorService watchdogExecutor;

    public ManagedLocalOllamaLifecycleBridge(RuntimeConfigService runtimeConfigService,
            ManagedLocalOllamaSupervisor supervisor) {
        this.runtimeConfigService = runtimeConfigService;
        this.supervisor = supervisor;
    }

    @PostConstruct
    public void initialize() {
        runStartupGate();
    }

    public synchronized void runStartupGate() {
        if (started) {
            return;
        }
        supervisor.startupCheck(isManagedLocalEmbeddingsActive(runtimeConfigService.getSelfEvolvingConfig()));
        startWatchdog();
        started = true;
    }

    synchronized void runWatchdogTick() {
        boolean localEmbeddingsActive = isManagedLocalEmbeddingsActive(runtimeConfigService.getSelfEvolvingConfig());
        if (!localEmbeddingsActive) {
            supervisor.embeddingsDisabled();
            return;
        }

        ManagedLocalOllamaStatus status = supervisor.currentStatus();
        ManagedLocalOllamaState state = status != null && status.getCurrentState() != null
                ? status.getCurrentState()
                : ManagedLocalOllamaState.DISABLED;
        boolean owned = status != null && Boolean.TRUE.equals(status.getOwned());
        switch (state) {
        case DISABLED:
            supervisor.startupCheck(true);
            break;
        case OWNED_READY:
        case OWNED_STARTING:
            monitorOwnedRuntime(true);
            break;
        case DEGRADED_START_TIMEOUT:
            if (owned) {
                ManagedLocalOllamaStatus ownedStatus = monitorOwnedRuntime(true);
                if (ownedStatus.getCurrentState() == ManagedLocalOllamaState.DEGRADED_START_TIMEOUT) {
                    supervisor.observeReadiness();
                }
                break;
            }
            supervisor.startupCheck(true);
            break;
        case DEGRADED_CRASHED:
        case DEGRADED_RESTART_BACKOFF:
            supervisor.attemptScheduledRetry();
            break;
        case EXTERNAL_READY:
            supervisor.pollExternalRuntime();
            break;
        case DEGRADED_EXTERNAL_LOST:
            supervisor.startupCheck(true);
            break;
        case DEGRADED_OUTDATED:
            if (owned) {
                monitorOwnedRuntime(true);
                break;
            }
            supervisor.pollExternalRuntime();
            break;
        case DEGRADED_MISSING_BINARY:
        case STOPPING:
            break;
        }
    }

    @PreDestroy
    public synchronized void stop() {
        stopWatchdog();
        supervisor.stop();
        started = false;
    }

    boolean isStarted() {
        return started;
    }

    private ManagedLocalOllamaStatus monitorOwnedRuntime(boolean allowRetry) {
        ManagedLocalOllamaStatus status = supervisor.pollOwnedProcess();
        ManagedLocalOllamaState state = status.getCurrentState();
        if (allowRetry && (state == ManagedLocalOllamaState.DEGRADED_CRASHED
                || state == ManagedLocalOllamaState.DEGRADED_RESTART_BACKOFF)) {
            return supervisor.attemptScheduledRetry();
        }
        return status;
    }

    private void startWatchdog() {
        if (watchdogExecutor != null) {
            return;
        }
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "managed-local-ollama-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdogExecutor.scheduleWithFixedDelay(
                this::safeRunWatchdogTick,
                WATCHDOG_INTERVAL_SECONDS,
                WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        if (watchdogExecutor == null) {
            return;
        }
        watchdogExecutor.shutdownNow();
        watchdogExecutor = null;
    }

    private void safeRunWatchdogTick() {
        try {
            runWatchdogTick();
        } catch (RuntimeException exception) {
            log.warn("Managed local ollama watchdog tick failed", exception);
        }
    }

    private boolean isManagedLocalEmbeddingsActive(RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig) {
        if (selfEvolvingConfig == null || !Boolean.TRUE.equals(selfEvolvingConfig.getEnabled())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics();
        if (tacticsConfig == null || !Boolean.TRUE.equals(tacticsConfig.getEnabled())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = tacticsConfig.getSearch();
        if (searchConfig == null || !"hybrid".equalsIgnoreCase(searchConfig.getMode())) {
            return false;
        }
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = searchConfig.getEmbeddings();
        if (embeddingsConfig == null || !Boolean.TRUE.equals(embeddingsConfig.getEnabled())) {
            return false;
        }
        if (!"ollama".equalsIgnoreCase(trimToNull(embeddingsConfig.getProvider()))) {
            return false;
        }
        return isLocalEndpoint(trimToNull(embeddingsConfig.getBaseUrl()));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isLocalEndpoint(String baseUrl) {
        if (baseUrl == null) {
            return true;
        }
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            return "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
