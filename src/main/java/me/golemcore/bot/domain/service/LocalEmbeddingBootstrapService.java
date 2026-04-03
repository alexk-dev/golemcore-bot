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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchStatus;
import me.golemcore.bot.port.outbound.OllamaProcessPort;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;

/**
 * Probes optional local embedding runtime state and degrades tactic search to
 * BM25 when needed.
 */
@Service
@Slf4j
public class LocalEmbeddingBootstrapService {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String MODE_BM25 = "bm25";
    private static final String MODE_HYBRID = "hybrid";
    private static final String PROVIDER_OLLAMA = "ollama";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://127.0.0.1:11434";
    private static final long LOCAL_RUNTIME_PROBE_TIMEOUT_SECONDS = 5L;

    private final RuntimeConfigService runtimeConfigService;
    private final TacticSearchMetricsService metricsService;
    private final Clock clock;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final SelfEvolvingTacticSearchStatusProjectionService tacticSearchStatusProjectionService;
    private final OllamaProcessPort ollamaProcessPort;

    public LocalEmbeddingBootstrapService(RuntimeConfigService runtimeConfigService,
            TacticSearchMetricsService metricsService,
            Clock clock,
            OkHttpClient okHttpClient,
            ObjectMapper objectMapper,
            SelfEvolvingTacticSearchStatusProjectionService tacticSearchStatusProjectionService,
            OllamaProcessPort ollamaProcessPort) {
        this.runtimeConfigService = runtimeConfigService;
        this.metricsService = metricsService;
        this.clock = clock;
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.tacticSearchStatusProjectionService = tacticSearchStatusProjectionService;
        this.ollamaProcessPort = ollamaProcessPort;
    }

    @PostConstruct
    public void initializeOnStartup() {
        initialize();
    }

    public TacticSearchStatus initialize() {
        return computeStatus(true);
    }

    public TacticSearchStatus probeStatus() {
        return computeStatus(false);
    }

    private TacticSearchStatus computeStatus(boolean recordMetrics) {
        if (tacticSearchStatusProjectionService != null) {
            TacticSearchStatus status = tacticSearchStatusProjectionService.projectCurrent();
            if (recordMetrics) {
                metricsService.recordStatus(status);
            }
            return status;
        }
        BootstrapContext context = resolveContext();
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = context.selfEvolvingConfig();
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = context.tacticsConfig();
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = context.localConfig();

        String configuredMode = context.configuredMode();
        String provider = context.provider();
        String baseUrl = context.baseUrl();
        String model = context.model();
        LocalRuntimeProbe runtimeProbe = probeLocalRuntime(provider);
        boolean runtimeHealthy = PROVIDER_OLLAMA.equals(provider) && baseUrl != null
                ? isRuntimeHealthy(baseUrl)
                : false;
        boolean runtimeInstalled = runtimeProbe.installed() || runtimeHealthy;
        String runtimeVersion = runtimeProbe.version();
        boolean modelAvailable = runtimeHealthy && model != null && PROVIDER_OLLAMA.equals(provider)
                ? hasModel(baseUrl, model)
                : false;

        if (!Boolean.TRUE.equals(selfEvolvingConfig.getEnabled()) || !Boolean.TRUE.equals(tacticsConfig.getEnabled())) {
            return activeStatus(MODE_BM25, "selfevolving tactics disabled", provider, model, runtimeInstalled,
                    runtimeHealthy, runtimeVersion, baseUrl, modelAvailable, localConfig, false, false, recordMetrics);
        }
        if (!Boolean.TRUE.equals(context.embeddingsConfig().getEnabled())) {
            return activeStatus(MODE_BM25, "embeddings disabled", provider, model, runtimeInstalled, runtimeHealthy,
                    runtimeVersion, baseUrl, modelAvailable, localConfig, false, false, recordMetrics);
        }
        if (!MODE_HYBRID.equals(configuredMode)) {
            return activeStatus(MODE_BM25, "lexical-only mode configured", provider, model, runtimeInstalled,
                    runtimeHealthy, runtimeVersion, baseUrl, modelAvailable, localConfig, false, false,
                    recordMetrics);
        }
        if (provider == null || baseUrl == null || model == null) {
            return failOpenOrThrow("embedding provider configuration incomplete", provider, model, runtimeInstalled,
                    runtimeHealthy, runtimeVersion, baseUrl, modelAvailable, localConfig, false, false, recordMetrics);
        }
        if (!PROVIDER_OLLAMA.equals(provider)) {
            return activeStatus(MODE_HYBRID, null, provider, model, true, true, null, baseUrl, true, localConfig,
                    false, false, recordMetrics);
        }

        if (!runtimeInstalled) {
            return failOpenOrThrow("Ollama is not installed on this machine", provider, model, false, false,
                    runtimeVersion, baseUrl, false, localConfig, false, false, recordMetrics);
        }
        if (!runtimeHealthy && Boolean.TRUE.equals(localConfig.getRequireHealthyRuntime())) {
            return failOpenOrThrow("Ollama is installed but not running at " + baseUrl, provider, model, true, false,
                    runtimeVersion, baseUrl, false, localConfig, false, false, recordMetrics);
        }

        boolean pullAttempted = false;
        boolean pullSucceeded = false;
        if (!modelAvailable && shouldPullOnStart(localConfig)) {
            pullAttempted = true;
            pullSucceeded = pullModel(baseUrl, model);
            modelAvailable = pullSucceeded || hasModel(baseUrl, model);
        }

        if (!modelAvailable) {
            String reason = pullAttempted
                    ? "Failed to install embedding model " + model + " in Ollama"
                    : "Embedding model " + model + " is not installed in Ollama";
            return failOpenOrThrow(reason, provider, model, true, runtimeHealthy, runtimeVersion, baseUrl, false,
                    localConfig, pullAttempted, pullSucceeded, recordMetrics);
        }

        TacticSearchStatus status = buildStatus(MODE_HYBRID, null, provider, model, false, true, runtimeHealthy,
                runtimeVersion, baseUrl, true, localConfig, pullAttempted, pullSucceeded);
        if (recordMetrics) {
            metricsService.recordStatus(status);
            log.info("[TacticSearch] Local embedding bootstrap active in {} mode for provider {} model {}",
                    status.getMode(), provider, model);
        }
        return status;
    }

    public TacticSearchStatus installConfiguredModel() {
        return installModel(null);
    }

    public TacticSearchStatus installModel(String requestedModel) {
        if (tacticSearchStatusProjectionService != null) {
            return installModelUsingProjectedStatus(requestedModel);
        }
        BootstrapContext context = resolveContext();
        String explicitModel = trimToNull(requestedModel);
        String provider = explicitModel != null ? PROVIDER_OLLAMA : context.provider();
        String baseUrl = resolveInstallBaseUrl(context, provider);
        String model = explicitModel != null ? explicitModel : context.model();
        if (!PROVIDER_OLLAMA.equals(provider)) {
            throw new IllegalStateException("local embedding provider is not configured");
        }
        if (baseUrl == null || model == null) {
            throw new IllegalStateException("embedding provider configuration incomplete");
        }
        LocalRuntimeProbe runtimeProbe = probeLocalRuntime(provider);
        boolean runtimeHealthy = isRuntimeHealthy(baseUrl);
        boolean runtimeInstalled = runtimeProbe.installed() || runtimeHealthy;
        if (!runtimeInstalled) {
            throw new IllegalStateException(
                    "Ollama is not installed on this machine. Install Ollama first, then retry model installation.");
        }
        if (!runtimeHealthy) {
            throw new IllegalStateException(
                    "Ollama is installed but not running at " + baseUrl
                            + ". Start the runtime, then retry model installation.");
        }

        boolean modelAvailable = hasModel(baseUrl, model);
        if (!modelAvailable) {
            boolean pullSucceeded = pullModel(baseUrl, model);
            modelAvailable = pullSucceeded || hasModel(baseUrl, model);
            if (!modelAvailable) {
                throw new IllegalStateException("Failed to install embedding model " + model + " in Ollama");
            }
            TacticSearchStatus status = buildStatus(MODE_HYBRID, null, provider, model, false,
                    true, true, runtimeProbe.version(), baseUrl, true, context.localConfig(), true, pullSucceeded);
            metricsService.recordStatus(status);
            return status;
        }

        TacticSearchStatus status = buildStatus(MODE_HYBRID, null, provider, model, false,
                true, true, runtimeProbe.version(), baseUrl, true, context.localConfig(), false, false);
        metricsService.recordStatus(status);
        return status;
    }

    private TacticSearchStatus installModelUsingProjectedStatus(String requestedModel) {
        TacticSearchStatus projectedStatus = tacticSearchStatusProjectionService.projectCurrent();
        BootstrapContext context = resolveContext();
        String explicitModel = trimToNull(requestedModel);
        String provider = explicitModel != null ? PROVIDER_OLLAMA : trimToNull(projectedStatus.getProvider());
        String model = explicitModel != null ? explicitModel : trimToNull(projectedStatus.getModel());
        String baseUrl = resolveInstallBaseUrl(projectedStatus, context, provider);
        String runtimeVersion = trimToNull(projectedStatus.getRuntimeVersion());
        boolean runtimeInstalled = Boolean.TRUE.equals(projectedStatus.getRuntimeInstalled());
        boolean runtimeHealthy = Boolean.TRUE.equals(projectedStatus.getRuntimeHealthy());
        if (!PROVIDER_OLLAMA.equals(provider)) {
            throw new IllegalStateException("local embedding provider is not configured");
        }
        if (baseUrl == null || model == null) {
            throw new IllegalStateException("embedding provider configuration incomplete");
        }
        if (explicitModel != null) {
            LocalRuntimeProbe runtimeProbe = probeLocalRuntime(provider);
            runtimeHealthy = isRuntimeHealthy(baseUrl);
            runtimeInstalled = runtimeProbe.installed() || runtimeHealthy;
            if (runtimeProbe.version() != null) {
                runtimeVersion = runtimeProbe.version();
            }
        }
        String runtimeMissingReason = explicitModel != null
                ? "Ollama is not installed on this machine. Install Ollama first, then retry model installation."
                : (projectedStatus.getReason() != null
                        ? projectedStatus.getReason()
                        : "Ollama is not installed on this machine");
        String runtimeUnhealthyReason = explicitModel != null
                ? "Ollama is installed but not running at " + baseUrl
                        + ". Start the runtime, then retry model installation."
                : (projectedStatus.getReason() != null
                        ? projectedStatus.getReason()
                        : "Ollama is installed but not running at " + baseUrl);
        if (!runtimeInstalled) {
            throw new IllegalStateException(runtimeMissingReason);
        }
        if (!runtimeHealthy) {
            throw new IllegalStateException(runtimeUnhealthyReason);
        }

        boolean modelAvailable = hasModel(baseUrl, model);
        if (!modelAvailable) {
            boolean pullSucceeded = pullModel(baseUrl, model);
            modelAvailable = pullSucceeded || hasModel(baseUrl, model);
            if (!modelAvailable) {
                throw new IllegalStateException("Failed to install embedding model " + model + " in Ollama");
            }
            TacticSearchStatus status = projectedStatus.toBuilder()
                    .mode(MODE_HYBRID)
                    .reason(null)
                    .provider(provider)
                    .model(model)
                    .degraded(false)
                    .runtimeInstalled(runtimeInstalled)
                    .runtimeHealthy(runtimeHealthy)
                    .runtimeVersion(runtimeVersion)
                    .baseUrl(baseUrl)
                    .modelAvailable(true)
                    .pullAttempted(true)
                    .pullSucceeded(true)
                    .updatedAt(clock.instant())
                    .build();
            metricsService.recordStatus(status);
            return status;
        }

        TacticSearchStatus status = projectedStatus.toBuilder()
                .mode(MODE_HYBRID)
                .reason(null)
                .provider(provider)
                .model(model)
                .degraded(false)
                .runtimeInstalled(runtimeInstalled)
                .runtimeHealthy(runtimeHealthy)
                .runtimeVersion(runtimeVersion)
                .baseUrl(baseUrl)
                .modelAvailable(true)
                .pullAttempted(false)
                .pullSucceeded(false)
                .updatedAt(clock.instant())
                .build();
        metricsService.recordStatus(status);
        return status;
    }

    protected LocalRuntimeProbe probeLocalRuntime(String provider) {
        if (!PROVIDER_OLLAMA.equals(provider)) {
            return LocalRuntimeProbe.notApplicable();
        }
        if (ollamaProcessPort == null) {
            return LocalRuntimeProbe.missing();
        }
        String installedVersion = trimToNull(ollamaProcessPort.getInstalledVersion());
        return installedVersion != null
                ? LocalRuntimeProbe.installed(normalizeRuntimeVersion(installedVersion))
                : LocalRuntimeProbe.missing();
    }

    protected boolean isRuntimeHealthy(String baseUrl) {
        Request request = new Request.Builder()
                .url(joinUrl(baseUrl, "/api/tags"))
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception exception) {
            return false;
        }
    }

    protected boolean hasModel(String baseUrl, String model) {
        Request request = new Request.Builder()
                .url(joinUrl(baseUrl, "/api/tags"))
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            JsonNode json = objectMapper.readTree(response.body().bytes());
            for (JsonNode node : json.path("models")) {
                String candidate = node.path("name").asText();
                if (model.equals(candidate)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    protected boolean pullModel(String baseUrl, String model) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "model", model,
                    "stream", false));
            Request request = new Request.Builder()
                    .url(joinUrl(baseUrl, "/api/pull"))
                    .post(RequestBody.create(payload, JSON))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean shouldPullOnStart(RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig) {
        return Boolean.TRUE.equals(localConfig.getAutoInstall()) && Boolean.TRUE.equals(localConfig.getPullOnStart());
    }

    private BootstrapContext resolveContext() {
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigService.getSelfEvolvingConfig();
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
        return new BootstrapContext(
                selfEvolvingConfig,
                tacticsConfig,
                searchConfig,
                embeddingsConfig,
                localConfig,
                normalizeMode(searchConfig.getMode()),
                normalizeProvider(embeddingsConfig.getProvider()),
                resolveBaseUrl(embeddingsConfig),
                trimToNull(embeddingsConfig.getModel()));
    }

    private String resolveBaseUrl(RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig) {
        String configuredBaseUrl = trimToNull(embeddingsConfig.getBaseUrl());
        String provider = normalizeProvider(embeddingsConfig.getProvider());
        if (configuredBaseUrl != null) {
            return configuredBaseUrl;
        }
        if (PROVIDER_OLLAMA.equals(provider)) {
            return DEFAULT_OLLAMA_BASE_URL;
        }
        return null;
    }

    private String resolveInstallBaseUrl(BootstrapContext context, String provider) {
        if (!PROVIDER_OLLAMA.equals(provider)) {
            return context.baseUrl();
        }
        if (PROVIDER_OLLAMA.equals(context.provider()) && context.baseUrl() != null) {
            return context.baseUrl();
        }
        return DEFAULT_OLLAMA_BASE_URL;
    }

    private String resolveInstallBaseUrl(TacticSearchStatus projectedStatus, BootstrapContext context,
            String provider) {
        if (!PROVIDER_OLLAMA.equals(provider)) {
            return context.baseUrl();
        }
        String projectedBaseUrl = trimToNull(projectedStatus.getBaseUrl());
        if (projectedBaseUrl != null) {
            return projectedBaseUrl;
        }
        return resolveInstallBaseUrl(context, provider);
    }

    private TacticSearchStatus failOpenOrThrow(String reason, String provider, String model, boolean runtimeInstalled,
            boolean runtimeHealthy, String runtimeVersion, String baseUrl, boolean modelAvailable,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            boolean pullAttempted, boolean pullSucceeded, boolean recordMetrics) {
        if (!Boolean.TRUE.equals(localConfig.getFailOpen())) {
            throw new IllegalStateException(reason);
        }
        TacticSearchStatus status = buildStatus(MODE_BM25, reason, provider, model, true, runtimeInstalled,
                runtimeHealthy, runtimeVersion, baseUrl, modelAvailable, localConfig, pullAttempted, pullSucceeded);
        if (recordMetrics) {
            metricsService.recordFallback(status.getMode(), status.getReason());
            metricsService.recordStatus(status);
            log.warn("[TacticSearch] Local embedding bootstrap degraded to {}: {}", status.getMode(),
                    status.getReason());
        }
        return status;
    }

    private TacticSearchStatus activeStatus(String mode, String reason, String provider, String model,
            boolean runtimeInstalled, boolean runtimeHealthy, String runtimeVersion, String baseUrl,
            boolean modelAvailable,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            boolean pullAttempted, boolean pullSucceeded, boolean recordMetrics) {
        TacticSearchStatus status = buildStatus(mode, reason, provider, model, false, runtimeInstalled, runtimeHealthy,
                runtimeVersion, baseUrl, modelAvailable, localConfig, pullAttempted, pullSucceeded);
        if (recordMetrics) {
            metricsService.recordStatus(status);
        }
        return status;
    }

    private TacticSearchStatus buildStatus(String mode, String reason, String provider, String model, boolean degraded,
            boolean runtimeInstalled, boolean runtimeHealthy, String runtimeVersion, String baseUrl,
            boolean modelAvailable,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            boolean pullAttempted, boolean pullSucceeded) {
        return TacticSearchStatus.builder()
                .mode(mode)
                .reason(reason)
                .provider(provider)
                .model(model)
                .degraded(degraded)
                .runtimeInstalled(runtimeInstalled)
                .runtimeHealthy(runtimeHealthy)
                .runtimeVersion(runtimeVersion)
                .baseUrl(baseUrl)
                .modelAvailable(modelAvailable)
                .autoInstallConfigured(Boolean.TRUE.equals(localConfig.getAutoInstall()))
                .pullOnStartConfigured(Boolean.TRUE.equals(localConfig.getPullOnStart()))
                .pullAttempted(pullAttempted)
                .pullSucceeded(pullSucceeded)
                .updatedAt(clock.instant())
                .build();
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

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding base URL must not be blank");
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + path;
    }

    private String normalizeRuntimeVersion(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String firstLine = Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse(output.trim());
        if (firstLine.regionMatches(true, 0, "ollama version is ", 0, "ollama version is ".length())) {
            return firstLine.substring("ollama version is ".length()).trim();
        }
        if (firstLine.regionMatches(true, 0, "ollama version ", 0, "ollama version ".length())) {
            return firstLine.substring("ollama version ".length()).trim();
        }
        return firstLine;
    }

    protected record LocalRuntimeProbe(boolean installed, String version) {
        static LocalRuntimeProbe installed(String version) {
            return new LocalRuntimeProbe(true, version);
        }

        static LocalRuntimeProbe missing() {
            return new LocalRuntimeProbe(false, null);
        }

        static LocalRuntimeProbe notApplicable() {
            return new LocalRuntimeProbe(false, null);
        }
    }

    private record BootstrapContext(
            RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig,
            RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig,
            RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsConfig embeddingsConfig,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            String configuredMode,
            String provider,
            String baseUrl,
            String model) {
    }
}
