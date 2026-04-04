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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding client for Ollama `/api/embed`.
 */
@Component
public class OllamaEmbeddingClient implements EmbeddingPort {

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public OllamaEmbeddingClient(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        try {
            Map<String, Object> body = Map.of(
                    "model", request.model(),
                    "input", request.inputs());
            Request httpRequest = new Request.Builder()
                    .url(joinUrl(request.baseUrl(), "/api/embed"))
                    .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                    .build();
            try (Response response = okHttpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Embedding request failed with status " + response.code());
                }
                ResponseBody responseBody = response.body();
                JsonNode json = objectMapper.readTree(responseBody.bytes());
                List<List<Double>> vectors = new ArrayList<>();
                for (JsonNode item : json.path("embeddings")) {
                    List<Double> vector = new ArrayList<>();
                    for (JsonNode value : item) {
                        vector.add(value.asDouble());
                    }
                    vectors.add(vector);
                }
                return new EmbeddingResponse(json.path("model").asText(request.model()), vectors);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fetch Ollama embeddings", exception);
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + path;
    }
}
