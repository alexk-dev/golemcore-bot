package me.golemcore.bot.domain.selfevolving;

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
import java.time.Instant;
import java.util.Locale;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaState;
import me.golemcore.bot.domain.model.selfevolving.tactic.ManagedLocalOllamaStatus;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.domain.selfevolving.tactic.ManagedLocalOllamaSupervisor;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.EmbeddingProviderIds;

/**
 * Builds a single tactic-search runtime status projection from runtime config
 * and the managed local Ollama supervisor.
 */
public class SelfEvolvingTacticSearchStatusProjectionService {

    private static final String MODE_BM25 = "bm25";
    private static final String MODE_HYBRID = "hybrid";
    private static final String PROVIDER_OLLAMA = EmbeddingProviderIds.OLLAMA;
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "qwen3-embedding:0.6b";

    private final String defaultEndpoint;
    private final String defaultSelectedModel;
    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private final ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor;
    private final Clock clock;

    public SelfEvolvingTacticSearchStatusProjectionService() {
        this(null, null, null, null, Clock.systemUTC());
    }

    public SelfEvolvingTacticSearchStatusProjectionService(String defaultEndpoint, String defaultSelectedModel) {
        this(defaultEndpoint, defaultSelectedModel, null, null, Clock.systemUTC());
    }

    public SelfEvolvingTacticSearchStatusProjectionService(
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor,
            Clock clock) {
        this(null, null, runtimeConfigPort, managedLocalOllamaSupervisor, clock);
    }

    private SelfEvolvingTacticSearchStatusProjectionService(
            String defaultEndpoint,
            String defaultSelectedModel,
            SelfEvolvingRuntimeConfigPort runtimeConfigPort,
            ManagedLocalOllamaSupervisor managedLocalOllamaSupervisor,
            Clock clock) {
        this.defaultEndpoint = defaultEndpoint;
        this.defaultSelectedModel = defaultSelectedModel;
        this.runtimeConfigPort = runtimeConfigPort;
        this.managedLocalOllamaSupervisor = managedLocalOllamaSupervisor;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public ManagedLocalOllamaStatus project(ManagedLocalOllamaStatus status) {
        return normalize(status, defaultEndpoint, defaultSelectedModel);
    }

    public TacticSearchStatus projectCurrent() {
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigPort != null
                ? runtimeConfigPort.getSelfEvolvingConfig()
                : RuntimeConfig.SelfEvolvingConfig.builder().build();
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = selfEvolvingConfig.getTactics() != null
                ? selfEvolvingConfig.getTactics()
                : new RuntimeConfig.SelfEvolvingTacticsConfig();
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = tacticsConfig.getSearch() != null
                ? tacticsConfig.getSearch()
                : new RuntimeConfig.SelfEvolvingTacticSearchConfig();
        RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig = searchConfig.getEmbeddings() != null
                ? searchConfig.getEmbeddings()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig();
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = embeddingsConfig.getLocal() != null
                ? embeddingsConfig.getLocal()
                : new RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig();
        String provider = resolveEffectiveProvider(embeddingsConfig.getProvider(), searchConfig.getMode());
        String model = resolveEffectiveModel(embeddingsConfig.getModel(), provider);
        String endpoint = resolveEndpoint(embeddingsConfig, provider);
        ManagedLocalOllamaStatus runtimeStatus = normalize(
                managedLocalOllamaSupervisor != null ? managedLocalOllamaSupervisor.currentStatus() : null,
                endpoint,
                model);

        if (!Boolean.TRUE.equals(selfEvolvingConfig.getEnabled())
                || !Boolean.TRUE.equals(tacticsConfig.getEnabled())) {
            return buildStatus(
                    MODE_BM25,
                    "selfevolving tactics disabled",
                    provider,
                    model,
                    false,
                    runtimeStatus,
                    false,
                    false,
                    localConfig,
                    clock.instant());
        }
        String configuredMode = normalizeMode(searchConfig.getMode());
        if (!MODE_HYBRID.equals(configuredMode)) {
            return buildStatus(
                    MODE_BM25,
                    "lexical-only mode configured",
                    provider,
                    model,
                    false,
                    runtimeStatus,
                    false,
                    false,
                    localConfig,
                    clock.instant());
        }

        if (provider == null || model == null || endpoint == null) {
            return buildStatus(
                    MODE_BM25,
                    "embedding provider configuration incomplete",
                    provider,
                    model,
                    true,
                    runtimeStatus,
                    false,
                    false,
                    localConfig,
                    clock.instant());
        }

        if (!PROVIDER_OLLAMA.equals(provider)) {
            return buildStatus(
                    MODE_HYBRID,
                    null,
                    provider,
                    model,
                    false,
                    runtimeStatus,
                    false,
                    false,
                    localConfig,
                    clock.instant()).toBuilder()
                    .runtimeInstalled(true)
                    .runtimeHealthy(true)
                    .modelAvailable(true)
                    .build();
        }

        return projectLocalOllamaStatus(provider, model, runtimeStatus, localConfig);
    }

    private TacticSearchStatus projectLocalOllamaStatus(
            String provider,
            String model,
            ManagedLocalOllamaStatus runtimeStatus,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig) {
        ManagedLocalOllamaState state = runtimeStatus.getCurrentState();
        String reason = switch (state != null ? state : ManagedLocalOllamaState.DISABLED) {
        case EXTERNAL_READY, OWNED_READY -> Boolean.TRUE.equals(runtimeStatus.getModelPresent())
                ? null
                : "Embedding model " + model + " is not installed in Ollama";
        case OWNED_STARTING -> "Managed Ollama is starting";
        case DEGRADED_MISSING_BINARY -> "Ollama is not installed on this machine";
        case DEGRADED_START_TIMEOUT, DEGRADED_CRASHED, DEGRADED_RESTART_BACKOFF, DEGRADED_EXTERNAL_LOST,
                DEGRADED_OUTDATED ->
            fallbackReason(runtimeStatus.getLastError(), "Managed Ollama is degraded");
        case STOPPING -> "Managed Ollama is stopping";
        case DISABLED -> "Local Ollama supervisor is disabled";
        };
        boolean healthy = state == ManagedLocalOllamaState.EXTERNAL_READY
                || state == ManagedLocalOllamaState.OWNED_READY;
        boolean degraded = !healthy || !Boolean.TRUE.equals(runtimeStatus.getModelPresent());
        String mode = healthy && Boolean.TRUE.equals(runtimeStatus.getModelPresent()) ? MODE_HYBRID : MODE_BM25;
        return buildStatus(
                mode,
                reason,
                provider,
                model,
                degraded,
                runtimeStatus,
                false,
                false,
                localConfig,
                clock.instant()).toBuilder()
                .runtimeInstalled(isRuntimeInstalled(runtimeStatus))
                .runtimeHealthy(healthy)
                .build();
    }

    private TacticSearchStatus buildStatus(
            String mode,
            String reason,
            String provider,
            String model,
            boolean degraded,
            ManagedLocalOllamaStatus runtimeStatus,
            boolean pullAttempted,
            boolean pullSucceeded,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            Instant now) {
        return TacticSearchStatus.builder()
                .mode(mode)
                .reason(reason)
                .provider(provider)
                .model(model)
                .degraded(degraded)
                .runtimeState(runtimeStatus.getCurrentState() != null
                        ? runtimeStatus.getCurrentState().name().toLowerCase(Locale.ROOT)
                        : null)
                .owned(Boolean.TRUE.equals(runtimeStatus.getOwned()))
                .runtimeInstalled(isRuntimeInstalled(runtimeStatus))
                .runtimeHealthy(runtimeStatus.getCurrentState() == ManagedLocalOllamaState.EXTERNAL_READY
                        || runtimeStatus.getCurrentState() == ManagedLocalOllamaState.OWNED_READY)
                .runtimeVersion(runtimeStatus.getVersion())
                .baseUrl(runtimeStatus.getEndpoint())
                .modelAvailable(Boolean.TRUE.equals(runtimeStatus.getModelPresent()))
                .restartAttempts(runtimeStatus.getRestartAttempts() != null ? runtimeStatus.getRestartAttempts() : 0)
                .nextRetryAt(runtimeStatus.getNextRetryAt())
                .nextRetryTime(runtimeStatus.getNextRetryTime())
                .autoInstallConfigured(Boolean.TRUE.equals(localConfig.getAutoInstall()))
                .pullOnStartConfigured(Boolean.TRUE.equals(localConfig.getPullOnStart()))
                .pullAttempted(pullAttempted)
                .pullSucceeded(pullSucceeded)
                .updatedAt(runtimeStatus.getUpdatedAt() != null ? runtimeStatus.getUpdatedAt() : now)
                .build();
    }

    private ManagedLocalOllamaStatus normalize(
            ManagedLocalOllamaStatus status,
            String fallbackEndpoint,
            String fallbackSelectedModel) {
        ManagedLocalOllamaStatus source = status != null ? status : ManagedLocalOllamaStatus.builder().build();
        Instant nextRetryAt = source.getNextRetryAt();

        return ManagedLocalOllamaStatus.builder()
                .currentState(
                        source.getCurrentState() != null ? source.getCurrentState() : ManagedLocalOllamaState.DISABLED)
                .owned(Boolean.TRUE.equals(source.getOwned()))
                .endpoint(source.getEndpoint() != null ? source.getEndpoint() : fallbackEndpoint)
                .version(source.getVersion())
                .selectedModel(
                        source.getSelectedModel() != null ? source.getSelectedModel() : fallbackSelectedModel)
                .modelPresent(source.getModelPresent())
                .lastError(source.getLastError())
                .restartAttempts(source.getRestartAttempts() != null ? source.getRestartAttempts() : 0)
                .nextRetryAt(nextRetryAt)
                .nextRetryTime(source.getNextRetryTime() != null
                        ? source.getNextRetryTime()
                        : (nextRetryAt != null ? nextRetryAt.toString() : null))
                .updatedAt(source.getUpdatedAt() != null ? source.getUpdatedAt() : Instant.EPOCH)
                .build();
    }

    private boolean isRuntimeInstalled(ManagedLocalOllamaStatus status) {
        if (status == null || status.getCurrentState() == null) {
            return false;
        }
        return status.getCurrentState() != ManagedLocalOllamaState.DEGRADED_MISSING_BINARY
                && status.getCurrentState() != ManagedLocalOllamaState.DISABLED;
    }

    private String resolveEndpoint(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig, String provider) {
        String configuredBaseUrl = trimToNull(embeddingsConfig.getBaseUrl());
        if (configuredBaseUrl != null) {
            return configuredBaseUrl;
        }
        if (PROVIDER_OLLAMA.equals(provider)) {
            return DEFAULT_OLLAMA_BASE_URL;
        }
        return defaultEndpoint;
    }

    private String resolveEffectiveProvider(String provider, String mode) {
        String normalizedProvider = normalizeProvider(provider);
        if (normalizedProvider != null) {
            return normalizedProvider;
        }
        return MODE_HYBRID.equals(normalizeMode(mode)) ? PROVIDER_OLLAMA : null;
    }

    private String resolveEffectiveModel(String model, String provider) {
        String normalizedModel = trimToNull(model);
        if (normalizedModel != null) {
            return normalizedModel;
        }
        return PROVIDER_OLLAMA.equals(provider) ? DEFAULT_OLLAMA_MODEL : null;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_BM25;
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String fallbackReason(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }
}
