package me.golemcore.bot.adapter.outbound.embedding;

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
import me.golemcore.bot.port.outbound.EmbeddingPort;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding client for OpenAI-compatible `/embeddings` APIs.
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingPort {

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleEmbeddingClient(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        try {
            return executeEmbeddingRequest(request);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to fetch OpenAI-compatible embeddings", exception);
        }
    }

    private EmbeddingResponse executeEmbeddingRequest(EmbeddingRequest request) throws IOException {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", request.model());
        body.put("input", request.inputs());
        if (request.dimensions() != null) {
            body.put("dimensions", request.dimensions());
        }
        Request.Builder requestBuilder = new Request.Builder()
                .url(joinUrl(request.baseUrl(), "/embeddings"))
                .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + request.apiKey().trim());
        }
        try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Embedding request failed with status " + response.code());
            }
            ResponseBody responseBody = response.body();
            byte[] responseBytes = responseBody.bytes();
            if (responseBytes.length == 0) {
                throw new IllegalStateException("Embedding response body is empty");
            }
            JsonNode json = objectMapper.readTree(responseBytes);
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : json.path("data")) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : item.path("embedding")) {
                    vector.add(value.asDouble());
                }
                vectors.add(vector);
            }
            return new EmbeddingResponse(json.path("model").asText(request.model()), vectors);
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + path;
    }
}
