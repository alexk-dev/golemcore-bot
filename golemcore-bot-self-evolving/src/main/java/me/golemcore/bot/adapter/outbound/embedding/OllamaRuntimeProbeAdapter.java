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
import me.golemcore.bot.port.outbound.OllamaRuntimeApiPort;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/**
 * Concrete Ollama runtime probe using HTTP API endpoints.
 */
public class OllamaRuntimeProbeAdapter implements OllamaRuntimeApiPort {

    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public OllamaRuntimeProbeAdapter(OkHttpClient okHttpClient, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isRuntimeReachable(String endpoint, Duration timeout) {
        try {
            Request request = new Request.Builder()
                    .url(buildLocalApiUrl(endpoint, "/api/tags"))
                    .get()
                    .build();
            try (Response response = buildClient(timeout).newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public String getRuntimeVersion(String endpoint, Duration timeout) {
        try {
            Request request = new Request.Builder()
                    .url(buildLocalApiUrl(endpoint, "/api/version"))
                    .get()
                    .build();
            try (Response response = buildClient(timeout).newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                JsonNode json = objectMapper.readTree(response.body().bytes());
                String version = json.path("version").asText(null);
                return version != null && !version.isBlank() ? version : null;
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    @Override
    public boolean hasModel(String endpoint, String model, Duration timeout) {
        try {
            Request request = new Request.Builder()
                    .url(buildLocalApiUrl(endpoint, "/api/tags"))
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
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    @Override
    public boolean pullModel(String endpoint, String model, Duration timeout) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "model", model,
                    "stream", false));
            Request request = new Request.Builder()
                    .url(buildLocalApiUrl(endpoint, "/api/pull"))
                    .post(RequestBody.create(payload, JSON))
                    .build();
            try (Response response = buildClient(timeout).newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private HttpUrl buildLocalApiUrl(String endpoint, String path) {
        LocalRuntimeEndpoint runtimeEndpoint = LocalRuntimeEndpoint.parse(endpoint);
        HttpUrl.Builder builder = new HttpUrl.Builder()
                .scheme(runtimeEndpoint.scheme())
                .host(runtimeEndpoint.host())
                .port(runtimeEndpoint.port());
        for (String segment : splitPathSegments(path)) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private OkHttpClient buildClient(Duration timeout) {
        Duration safeTimeout = timeout != null && !timeout.isNegative() && !timeout.isZero()
                ? timeout
                : Duration.ofMillis(1);
        return okHttpClient.newBuilder()
                .callTimeout(safeTimeout)
                .readTimeout(safeTimeout)
                .writeTimeout(safeTimeout)
                .build();
    }

    private java.util.List<String> splitPathSegments(String path) {
        String normalizedPath = trimToNull(path);
        if (normalizedPath == null) {
            return java.util.List.of();
        }
        return Arrays.stream(normalizedPath.split("/"))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .toList();
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

    private record LocalRuntimeEndpoint(String scheme, String host, int port) {

        private static LocalRuntimeEndpoint parse(String endpoint) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("Embedding base URL must not be blank");
            }
            URI uri = URI.create(endpoint.trim());
            validate(uri);
            String scheme = normalizeScheme(uri.getScheme());
            String host = normalizeHost(uri.getHost());
            if (host == null) {
                throw new IllegalArgumentException("Embedding base URL must target a local Ollama endpoint");
            }
            return new LocalRuntimeEndpoint(scheme, host, resolvePort(uri.getPort(), scheme));
        }

        private static void validate(URI uri) {
            if (trimToNull(uri.getUserInfo()) != null
                    || trimToNull(uri.getQuery()) != null
                    || trimToNull(uri.getFragment()) != null) {
                throw new IllegalArgumentException("Embedding base URL must not contain user info, query, or fragment");
            }
            String normalizedPath = trimToNull(uri.getPath());
            if (normalizedPath != null && !"/".equals(normalizedPath)) {
                throw new IllegalArgumentException("Embedding base URL must not contain a path prefix");
            }
        }

        private static String normalizeScheme(String scheme) {
            String normalizedScheme = trimToNull(scheme);
            if (normalizedScheme == null) {
                return "http";
            }
            if ("http".equalsIgnoreCase(normalizedScheme)) {
                return "http";
            }
            if ("https".equalsIgnoreCase(normalizedScheme)) {
                return "https";
            }
            throw new IllegalArgumentException("Embedding base URL must use http or https");
        }

        private static String normalizeHost(String host) {
            String normalizedHost = trimToNull(host);
            if (normalizedHost == null) {
                return null;
            }
            if ("127.0.0.1".equals(normalizedHost)) {
                return "127.0.0.1";
            }
            if ("localhost".equalsIgnoreCase(normalizedHost)) {
                return "localhost";
            }
            if ("::1".equals(normalizedHost) || "[::1]".equals(normalizedHost)) {
                return "::1";
            }
            return null;
        }

        private static int resolvePort(int port, String scheme) {
            if (port > 0 && port <= 65535) {
                return port;
            }
            return "https".equals(scheme) ? 443 : 80;
        }

        private static String trimToNull(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }
}
