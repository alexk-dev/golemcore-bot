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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Clock;
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

    private final RuntimeConfigService runtimeConfigService;
    private final TacticSearchMetricsService metricsService;
    private final Clock clock;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public LocalEmbeddingBootstrapService(RuntimeConfigService runtimeConfigService,
            TacticSearchMetricsService metricsService,
            Clock clock,
            OkHttpClient okHttpClient,
            ObjectMapper objectMapper) {
        this.runtimeConfigService = runtimeConfigService;
        this.metricsService = metricsService;
        this.clock = clock;
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeOnStartup() {
        initialize();
    }

    public TacticSearchStatus initialize() {
        BootstrapContext context = resolveContext();
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = context.selfEvolvingConfig();
        RuntimeConfig.SelfEvolvingTacticsConfig tacticsConfig = context.tacticsConfig();
        RuntimeConfig.SelfEvolvingTacticSearchConfig searchConfig = context.searchConfig();
        RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig = context.localConfig();

        String configuredMode = context.configuredMode();
        String provider = context.provider();
        String baseUrl = context.baseUrl();
        String model = context.model();

        if (!Boolean.TRUE.equals(selfEvolvingConfig.getEnabled()) || !Boolean.TRUE.equals(tacticsConfig.getEnabled())) {
            return activeStatus(MODE_BM25, "selfevolving tactics disabled", provider, model, false, false, localConfig,
                    false, false);
        }
        if (!Boolean.TRUE.equals(context.embeddingsConfig().getEnabled())) {
            return activeStatus(MODE_BM25, "embeddings disabled", provider, model, false, false, localConfig, false,
                    false);
        }
        if (!MODE_HYBRID.equals(configuredMode)) {
            return activeStatus(MODE_BM25, "lexical-only mode configured", provider, model, false, false, localConfig,
                    false, false);
        }
        if (provider == null || baseUrl == null || model == null) {
            return failOpenOrThrow("embedding provider configuration incomplete", provider, model, false, false,
                    localConfig, false, false);
        }
        if (!PROVIDER_OLLAMA.equals(provider)) {
            return activeStatus(MODE_HYBRID, null, provider, model, true, true, localConfig, false, false);
        }

        boolean runtimeHealthy = isRuntimeHealthy(baseUrl);
        if (!runtimeHealthy && Boolean.TRUE.equals(localConfig.getRequireHealthyRuntime())) {
            return failOpenOrThrow("local embedding runtime unavailable", provider, model, false, false, localConfig,
                    false, false);
        }

        boolean modelAvailable = runtimeHealthy && hasModel(baseUrl, model);
        boolean pullAttempted = false;
        boolean pullSucceeded = false;
        if (!modelAvailable && shouldPullOnStart(localConfig)) {
            pullAttempted = true;
            pullSucceeded = pullModel(baseUrl, model);
            modelAvailable = pullSucceeded || hasModel(baseUrl, model);
        }

        if (!modelAvailable) {
            String reason = pullAttempted ? "local embedding model pull failed" : "local embedding model unavailable";
            return failOpenOrThrow(reason, provider, model, runtimeHealthy, false, localConfig, pullAttempted,
                    pullSucceeded);
        }

        TacticSearchStatus status = buildStatus(MODE_HYBRID, null, provider, model, false, runtimeHealthy, true,
                localConfig, pullAttempted, pullSucceeded);
        metricsService.recordStatus(status);
        log.info("[TacticSearch] Local embedding bootstrap active in {} mode for provider {} model {}",
                status.getMode(), provider, model);
        return status;
    }

    public TacticSearchStatus installConfiguredModel() {
        BootstrapContext context = resolveContext();
        if (!PROVIDER_OLLAMA.equals(context.provider())) {
            throw new IllegalStateException("local embedding provider is not configured");
        }
        if (context.baseUrl() == null || context.model() == null) {
            throw new IllegalStateException("embedding provider configuration incomplete");
        }
        if (!isRuntimeHealthy(context.baseUrl())) {
            throw new IllegalStateException("local embedding runtime unavailable");
        }

        boolean modelAvailable = hasModel(context.baseUrl(), context.model());
        if (!modelAvailable) {
            boolean pullSucceeded = pullModel(context.baseUrl(), context.model());
            modelAvailable = pullSucceeded || hasModel(context.baseUrl(), context.model());
            if (!modelAvailable) {
                throw new IllegalStateException("local embedding model pull failed");
            }
            TacticSearchStatus status = buildStatus(MODE_HYBRID, null, context.provider(), context.model(), false,
                    true, true, context.localConfig(), true, pullSucceeded);
            metricsService.recordStatus(status);
            return status;
        }

        TacticSearchStatus status = buildStatus(MODE_HYBRID, null, context.provider(), context.model(), false,
                true, true, context.localConfig(), false, false);
        metricsService.recordStatus(status);
        return status;
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
                trimToNull(embeddingsConfig.getBaseUrl()),
                trimToNull(embeddingsConfig.getModel()));
    }

    private TacticSearchStatus failOpenOrThrow(String reason, String provider, String model, boolean runtimeHealthy,
            boolean modelAvailable, RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            boolean pullAttempted, boolean pullSucceeded) {
        if (!Boolean.TRUE.equals(localConfig.getFailOpen())) {
            throw new IllegalStateException(reason);
        }
        TacticSearchStatus status = buildStatus(MODE_BM25, reason, provider, model, true, runtimeHealthy,
                modelAvailable, localConfig, pullAttempted, pullSucceeded);
        metricsService.recordFallback(status.getMode(), status.getReason());
        metricsService.recordStatus(status);
        log.warn("[TacticSearch] Local embedding bootstrap degraded to {}: {}", status.getMode(), status.getReason());
        return status;
    }

    private TacticSearchStatus activeStatus(String mode, String reason, String provider, String model,
            boolean runtimeHealthy, boolean modelAvailable,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            boolean pullAttempted, boolean pullSucceeded) {
        TacticSearchStatus status = buildStatus(mode, reason, provider, model, false, runtimeHealthy, modelAvailable,
                localConfig, pullAttempted, pullSucceeded);
        metricsService.recordStatus(status);
        return status;
    }

    private TacticSearchStatus buildStatus(String mode, String reason, String provider, String model, boolean degraded,
            boolean runtimeHealthy, boolean modelAvailable,
            RuntimeConfig.SelfEvolvingTacticEmbeddingsLocalConfig localConfig,
            boolean pullAttempted, boolean pullSucceeded) {
        return TacticSearchStatus.builder()
                .mode(mode)
                .reason(reason)
                .provider(provider)
                .model(model)
                .degraded(degraded)
                .runtimeHealthy(runtimeHealthy)
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
