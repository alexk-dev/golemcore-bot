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

package me.golemcore.bot.adapter.outbound.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client for the OpenAI {@code /v1/responses} endpoint.
 *
 * <p>
 * Supports both synchronous and streaming (SSE) modes. When streaming
 * encounters a connection-level error, it falls back to a synchronous
 * {@code /v1/responses} call (not to the legacy {@code /v1/chat/completions}).
 *
 * <p>
 * Not a Spring bean — created per-provider by {@code Langchain4jAdapter} and
 * cached for reuse.
 */
public class OpenAiResponsesClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String RESPONSES_PATH = "/responses";

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ResponsesRequestBuilder requestBuilder;
    private final ResponsesEventParser eventParser;
    private final ObjectMapper objectMapper;

    public OpenAiResponsesClient(RuntimeConfig.LlmProviderConfig config, ObjectMapper objectMapper) {
        this.apiKey = Secret.valueOrEmpty(config.getApiKey());
        this.baseUrl = resolveBaseUrl(config.getBaseUrl());
        int timeoutSeconds = config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.objectMapper = objectMapper;
        this.requestBuilder = new ResponsesRequestBuilder(objectMapper);
        this.eventParser = new ResponsesEventParser(objectMapper);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Synchronous chat — sends a request to {@code /v1/responses} with
     * {@code stream: false} and blocks until the full response is received.
     */
    public LlmResponse chat(LlmRequest request) {
        ObjectNode body = requestBuilder.buildRequestBody(request, false);
        HttpRequest httpRequest = buildHttpRequest(body);

        log.debug("[OpenAI Responses] Sending sync request for model: {}", request.getModel());
        try {
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            validateResponse(response);
            return eventParser.parseSyncResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OpenAI Responses API request was interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenAI Responses API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Streaming chat — sends a request to {@code /v1/responses} with
     * {@code stream: true} and returns a {@link Flux} of incremental chunks.
     *
     * <p>
     * On streaming connection failure, falls back to a sync call and emits the full
     * response as a single chunk.
     */
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        return Flux.create(sink -> {
            try {
                streamSse(request, sink);
            } catch (Exception e) {
                log.warn("[OpenAI Responses] Streaming failed for model {}, falling back to sync: {}",
                        request.getModel(), e.getMessage());
                fallbackToSync(request, sink);
            }
        });
    }

    private void streamSse(LlmRequest request, FluxSink<LlmChunk> sink) {
        ObjectNode body = requestBuilder.buildRequestBody(request, true);
        HttpRequest httpRequest = buildHttpRequest(body);

        log.debug("[OpenAI Responses] Sending streaming request for model: {}", request.getModel());
        try {
            HttpResponse<InputStream> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = readFully(response.body());
                log.warn("[OpenAI Responses] Streaming request returned HTTP {}, falling back to sync. Body: {}",
                        response.statusCode(), truncate(errorBody, 500));
                fallbackToSync(request, sink);
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String currentEventType = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink.isCancelled()) {
                        break;
                    }
                    if (line.startsWith("event: ")) {
                        currentEventType = line.substring("event: ".length()).trim();
                    } else if (line.startsWith("data: ")) {
                        String data = line.substring("data: ".length());
                        if ("[DONE]".equals(data.trim())) {
                            break;
                        }
                        if (currentEventType != null) {
                            LlmChunk chunk = eventParser.parseStreamEvent(currentEventType, data);
                            if (chunk != null) {
                                sink.next(chunk);
                                if (chunk.isDone()) {
                                    break;
                                }
                            }
                        }
                    } else if (line.isEmpty()) {
                        // SSE event boundary — reset
                        currentEventType = null;
                    }
                }
            }
            sink.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.error(new RuntimeException("OpenAI Responses streaming was interrupted", e));
        } catch (Exception e) {
            log.warn("[OpenAI Responses] SSE read error for model {}, falling back to sync: {}",
                    request.getModel(), e.getMessage());
            fallbackToSync(request, sink);
        }
    }

    private void fallbackToSync(LlmRequest request, FluxSink<LlmChunk> sink) {
        log.info("[OpenAI Responses] Executing sync fallback for model: {}", request.getModel());
        try {
            LlmResponse response = chat(request);
            sink.next(LlmChunk.builder()
                    .text(response.getContent())
                    .done(true)
                    .usage(response.getUsage())
                    .finishReason(response.getFinishReason())
                    .build());
            sink.complete();
        } catch (Exception fallbackError) {
            log.error("[OpenAI Responses] Sync fallback also failed for model {}: {}",
                    request.getModel(), fallbackError.getMessage());
            sink.error(fallbackError);
        }
    }

    private HttpRequest buildHttpRequest(ObjectNode body) {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + RESPONSES_PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
    }

    private void validateResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() != null ? truncate(response.body(), 1000) : "";
            throw new RuntimeException(
                    "OpenAI Responses API returned HTTP " + response.statusCode() + ": " + body);
        }
    }

    private String resolveBaseUrl(String configBaseUrl) {
        if (configBaseUrl == null || configBaseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String url = configBaseUrl.trim();
        // Strip trailing /v1 if present — we append our own path
        if (url.endsWith("/v1")) {
            return url;
        }
        if (url.endsWith("/v1/")) {
            return url.substring(0, url.length() - 1);
        }
        // If no version path, append /v1
        if (!url.contains("/v1")) {
            if (url.endsWith("/")) {
                return url + "v1";
            }
            return url + "/v1";
        }
        return url;
    }

    private String readFully(InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
