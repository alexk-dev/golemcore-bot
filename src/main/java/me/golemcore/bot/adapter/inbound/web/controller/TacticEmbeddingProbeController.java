package me.golemcore.bot.adapter.inbound.web.controller;

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
import me.golemcore.bot.adapter.outbound.embedding.OpenAiCompatibleEmbeddingClient;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.EmbeddingPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Dashboard endpoint that probes an OpenAI-compatible embedding provider using
 * the current form values plus the stored API key fallback.
 */
@RestController
@RequestMapping("/api/self-evolving/tactics/embeddings")
@Slf4j
public class TacticEmbeddingProbeController {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "text-embedding-3-large";
    private static final String PROBE_INPUT = "golemcore embedding connectivity check";

    private final OpenAiCompatibleEmbeddingClient openAiCompatibleEmbeddingClient;
    private final RuntimeConfigService runtimeConfigService;

    public TacticEmbeddingProbeController(
            OpenAiCompatibleEmbeddingClient openAiCompatibleEmbeddingClient,
            RuntimeConfigService runtimeConfigService) {
        this.openAiCompatibleEmbeddingClient = openAiCompatibleEmbeddingClient;
        this.runtimeConfigService = runtimeConfigService;
    }

    public record ProbeRequest(
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions,
            Integer timeoutMs) {
    }

    public record ProbeResponse(
            boolean ok,
            String model,
            Integer dimensions,
            Integer vectorLength,
            String baseUrl,
            String error) {
    }

    @PostMapping("/probe")
    public Mono<ResponseEntity<ProbeResponse>> probeRemoteEmbedding(
            @RequestBody(required = false) ProbeRequest request) {
        return Mono.fromCallable(() -> executeProbe(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    private ProbeResponse executeProbe(ProbeRequest request) {
        String baseUrl = firstNonBlank(request != null ? request.baseUrl() : null, DEFAULT_BASE_URL);
        String model = firstNonBlank(request != null ? request.model() : null, DEFAULT_MODEL);
        String apiKey = resolveApiKey(request != null ? request.apiKey() : null);
        Integer dimensions = request != null ? request.dimensions() : null;
        Integer timeoutMs = request != null ? request.timeoutMs() : null;
        try {
            EmbeddingPort.EmbeddingResponse response = openAiCompatibleEmbeddingClient.embed(
                    new EmbeddingPort.EmbeddingRequest(
                            baseUrl,
                            apiKey,
                            model,
                            dimensions,
                            timeoutMs,
                            List.of(PROBE_INPUT)));
            int vectorLength = response.vectors().isEmpty() ? 0 : response.vectors().get(0).size();
            return new ProbeResponse(true, response.model(), vectorLength > 0 ? vectorLength : null,
                    vectorLength, baseUrl, null);
        } catch (RuntimeException exception) { // NOSONAR broad catch to surface network/auth failures to UI
            log.warn("[TacticSearch] Remote embedding probe failed: {}", exception.getMessage());
            String message = exception.getMessage() != null ? exception.getMessage()
                    : exception.getClass().getSimpleName();
            Throwable cause = exception.getCause();
            if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = message + " (" + cause.getMessage() + ")";
            }
            return new ProbeResponse(false, model, dimensions, null, baseUrl, message);
        }
    }

    private String resolveApiKey(String requestedApiKey) {
        if (requestedApiKey != null && !requestedApiKey.isBlank()) {
            return requestedApiKey.trim();
        }
        RuntimeConfig.SelfEvolvingConfig selfEvolvingConfig = runtimeConfigService.getSelfEvolvingConfig();
        if (selfEvolvingConfig == null || selfEvolvingConfig.getTactics() == null
                || selfEvolvingConfig.getTactics().getSearch() == null
                || selfEvolvingConfig.getTactics().getSearch().getEmbeddings() == null) {
            return null;
        }
        String stored = selfEvolvingConfig.getTactics().getSearch().getEmbeddings().getApiKey();
        return stored != null && !stored.isBlank() ? stored.trim() : null;
    }

    private String firstNonBlank(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate.trim() : fallback;
    }
}
