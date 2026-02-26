package me.golemcore.bot.plugin.builtin.rag.adapter;

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

import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.RagPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LightRAG adapter — communicates with LightRAG REST API over HTTP.
 *
 * <p>
 * LightRAG is a graph-based RAG system that indexes conversation history and
 * retrieves relevant context for queries. This adapter uses OkHttp to call the
 * LightRAG REST API (typically running in a Docker container).
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /query - Retrieve relevant context for a query
 * <li>POST /documents/text - Index conversation content
 * <li>GET /health - Health check
 * </ul>
 *
 * <p>
 * Integration:
 * <ul>
 * <li>{@link me.golemcore.bot.domain.system.RagIndexingSystem} indexes
 * conversations after MemoryPersistSystem
 * <li>{@link me.golemcore.bot.domain.system.ContextBuildingSystem} queries RAG
 * and adds context to system prompt
 * </ul>
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>RuntimeConfig.rag.enabled - Enable/disable RAG feature
 * <li>RuntimeConfig.rag.url - LightRAG API base URL
 * <li>RuntimeConfig.rag.apiKey - Optional API key
 * <li>RuntimeConfig.rag.queryMode - Query mode (local/global/hybrid/naive)
 * <li>RuntimeConfig.rag.timeoutSeconds - HTTP timeout
 * </ul>
 *
 * @see me.golemcore.bot.port.outbound.RagPort
 * @see me.golemcore.bot.domain.system.RagIndexingSystem
 */
@Component
@Slf4j
public class LightRagAdapter implements RagPort {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final RuntimeConfigService runtimeConfigService;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LightRagAdapter(RuntimeConfigService runtimeConfigService,
            OkHttpClient baseHttpClient, ObjectMapper objectMapper) {
        this.runtimeConfigService = runtimeConfigService;
        this.objectMapper = objectMapper;

        // Build a dedicated client with RAG-specific timeout
        int timeoutSeconds = runtimeConfigService.getRagTimeoutSeconds();
        this.httpClient = baseHttpClient.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public CompletableFuture<String> query(String query, String mode) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture("");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = runtimeConfigService.getRagUrl() + "/query";
                String body = objectMapper.writeValueAsString(new QueryRequest(query, mode));

                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(body, JSON));
                addApiKeyHeader(requestBuilder);

                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    ResponseBody responseBody = response.body();
                    if (!response.isSuccessful() || responseBody == null) {
                        log.warn("[RAG] Query failed: HTTP {}", response.code());
                        return "";
                    }

                    String responseStr = responseBody.string();
                    return parseQueryResponse(responseStr);
                }
            } catch (Exception e) {
                log.warn("[RAG] Query error: {}", e.getMessage());
                return "";
            }
        });
    }

    @Override
    public CompletableFuture<Void> index(String content) {
        if (!isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                String url = runtimeConfigService.getRagUrl() + "/documents/text";
                String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                        .withZone(ZoneOffset.UTC).format(Instant.now());
                String fileSource = "conv_" + timestamp + ".txt";
                if (content == null || content.isBlank()) {
                    log.debug("[RAG] Skipping index of empty content");
                    return;
                }
                String body = objectMapper.writeValueAsString(new IndexRequest(content, fileSource));

                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(body, JSON));
                addApiKeyHeader(requestBuilder);

                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    if (!response.isSuccessful()) {
                        log.warn("[RAG] Index failed: HTTP {}", response.code());
                    } else {
                        log.debug("[RAG] Indexed {} chars", content.length());
                    }
                }
            } catch (IOException e) {
                log.warn("[RAG] Index error: {}", e.getMessage());
            }
        });
    }

    @Override
    public boolean isAvailable() {
        return runtimeConfigService.isRagEnabled();
    }

    /**
     * Check health of the LightRAG server (for diagnostics, not called on every
     * request).
     */
    public boolean isHealthy() {
        if (!runtimeConfigService.isRagEnabled()) {
            return false;
        }
        try {
            String url = runtimeConfigService.getRagUrl() + "/health";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            log.debug("[RAG] Health check failed: {}", e.getMessage());
            return false;
        }
    }

    private void addApiKeyHeader(Request.Builder builder) {
        String apiKey = runtimeConfigService.getRagApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    private String parseQueryResponse(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            // LightRAG returns {"response": "..."}
            if (node.has("response")) {
                return node.get("response").asText("");
            }
            // Fallback: treat entire body as text
            return responseBody.trim();
        } catch (JsonProcessingException e) {
            log.debug("[RAG] Failed to parse query response, using raw text");
            return responseBody.trim();
        }
    }

    // Request DTOs
    record QueryRequest(String query, String mode) {
    }

    record IndexRequest(String text, String file_source) {
    }
}
