package me.golemcore.bot.application.selfevolving.tactic;

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

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.EmbeddingClientResolverPort;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import me.golemcore.bot.port.outbound.EmbeddingProviderIds;

/**
 * Application service that probes embedding connectivity without exposing the
 * outbound adapter to the inbound controller.
 */
@Slf4j
public class TacticEmbeddingProbeService {

    private static final String DEFAULT_PROVIDER = EmbeddingProviderIds.OPENAI_COMPATIBLE;
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "text-embedding-3-large";
    private static final String PROBE_INPUT = "golemcore embedding connectivity check";

    private final EmbeddingClientResolverPort embeddingClientResolverPort;
    private final SelfEvolvingRuntimeConfigPort runtimeConfigPort;

    public TacticEmbeddingProbeService(
            EmbeddingClientResolverPort embeddingClientResolverPort,
            SelfEvolvingRuntimeConfigPort runtimeConfigPort) {
        this.embeddingClientResolverPort = embeddingClientResolverPort;
        this.runtimeConfigPort = runtimeConfigPort;
    }

    public record ProbeRequest(
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions,
            Integer timeoutMs) {
    }

    public record EmbeddingProbeResult(
            boolean ok,
            String model,
            Integer dimensions,
            Integer vectorLength,
            String baseUrl,
            String error) {
    }

    public EmbeddingProbeResult probe(ProbeRequest request) {
        String baseUrl = firstNonBlank(request != null ? request.baseUrl() : null, DEFAULT_BASE_URL);
        String model = firstNonBlank(request != null ? request.model() : null, DEFAULT_MODEL);
        String apiKey = resolveApiKey(request != null ? request.apiKey() : null);
        Integer dimensions = request != null ? request.dimensions() : null;
        Integer timeoutMs = request != null ? request.timeoutMs() : null;
        try {
            EmbeddingPort client = embeddingClientResolverPort.resolve(DEFAULT_PROVIDER);
            EmbeddingPort.EmbeddingResponse response = client.embed(new EmbeddingPort.EmbeddingRequest(
                    baseUrl,
                    apiKey,
                    model,
                    dimensions,
                    timeoutMs,
                    List.of(PROBE_INPUT)));
            int vectorLength = response.vectors().isEmpty() ? 0 : response.vectors().getFirst().size();
            return new EmbeddingProbeResult(
                    true,
                    response.model(),
                    vectorLength > 0 ? vectorLength : null,
                    vectorLength,
                    baseUrl,
                    null);
        } catch (RuntimeException exception) { // NOSONAR broad catch to surface network/auth failures to UI
            log.warn("[TacticSearch] Remote embedding probe failed: {}", exception.getMessage());
            return new EmbeddingProbeResult(
                    false,
                    model,
                    dimensions,
                    null,
                    baseUrl,
                    formatError(exception));
        }
    }

    private String resolveApiKey(String requestedApiKey) {
        if (requestedApiKey != null && !requestedApiKey.isBlank()) {
            return requestedApiKey.trim();
        }
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigPort.getSelfEvolvingConfig();
        if (selfEvolvingConfig.getTactics() == null
                || selfEvolvingConfig.getTactics().getSearch() == null
                || selfEvolvingConfig.getTactics().getSearch().getEmbeddings() == null) {
            return null;
        }
        Secret stored = selfEvolvingConfig.getTactics().getSearch().getEmbeddings().getApiKey();
        String value = Secret.valueOrEmpty(stored);
        return value.isBlank() ? null : value.trim();
    }

    private String formatError(RuntimeException exception) {
        String message = exception.getMessage() != null ? exception.getMessage()
                : exception.getClass().getSimpleName();
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message = message + " (" + cause.getMessage() + ")";
        }
        return message;
    }

    private String firstNonBlank(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate.trim() : fallback;
    }
}
