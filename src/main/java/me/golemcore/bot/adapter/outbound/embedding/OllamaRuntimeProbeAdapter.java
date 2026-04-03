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
import java.time.Duration;
import me.golemcore.bot.port.outbound.OllamaRuntimeProbePort;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Concrete Ollama runtime probe using HTTP API endpoints.
 */
public class OllamaRuntimeProbeAdapter implements OllamaRuntimeProbePort {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public OllamaRuntimeProbeAdapter(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isRuntimeReachable(String endpoint, Duration timeout) {
        Request request = new Request.Builder()
                .url(joinUrl(endpoint, "/api/tags"))
                .get()
                .build();
        try (Response response = buildClient(timeout).newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public String getRuntimeVersion(String endpoint, Duration timeout) {
        Request request = new Request.Builder()
                .url(joinUrl(endpoint, "/api/version"))
                .get()
                .build();
        try (Response response = buildClient(timeout).newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body().bytes());
            String version = json.path("version").asText(null);
            return version != null && !version.isBlank() ? version : null;
        } catch (Exception exception) {
            return null;
        }
    }

    @Override
    public boolean hasModel(String endpoint, String model, Duration timeout) {
        Request request = new Request.Builder()
                .url(joinUrl(endpoint, "/api/tags"))
                .get()
                .build();
        try (Response response = buildClient(timeout).newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            JsonNode json = objectMapper.readTree(response.body().bytes());
            for (JsonNode item : json.path("models")) {
                String candidate = item.path("name").asText(null);
                if (matchesModelName(model, candidate)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + path;
    }

    private OkHttpClient buildClient(Duration timeout) {
        Duration safeTimeout = timeout != null && !timeout.isNegative() && !timeout.isZero()
                ? timeout
                : Duration.ofMillis(1);
        return okHttpClient.newBuilder()
                .callTimeout(safeTimeout)
                .build();
    }

    private boolean matchesModelName(String requestedModel, String candidateModel) {
        String normalizedRequested = trimToNull(requestedModel);
        String normalizedCandidate = trimToNull(candidateModel);
        if (normalizedRequested == null || normalizedCandidate == null) {
            return false;
        }
        if (normalizedRequested.equals(normalizedCandidate)) {
            return true;
        }
        return normalizedCandidate.equals(normalizedRequested + ":latest")
                || normalizedRequested.equals(normalizedCandidate + ":latest");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
