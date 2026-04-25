package me.golemcore.bot.adapter.outbound.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import me.golemcore.bot.port.outbound.ProviderModelDiscoveryPort;
import org.springframework.stereotype.Component;

@Component
public class HttpProviderModelDiscoveryAdapter implements ProviderModelDiscoveryPort {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public DiscoveryResponse discover(DiscoveryRequest request) {
        HttpRequest httpRequest = buildHttpRequest(request);
        try {
            HttpResponse<String> response = buildHttpClient(request).send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new DiscoveryResponse(response.statusCode(), parseDocuments(response.body()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model discovery request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Model discovery request failed: " + exception.getMessage(), exception);
        }
    }

    protected HttpClient buildHttpClient(DiscoveryRequest request) {
        Duration timeout = request.timeout() != null ? request.timeout() : Duration.ofSeconds(20);
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    protected HttpRequest buildHttpRequest(DiscoveryRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(request.uri())
                .timeout(request.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", request.userAgent())
                .GET();
        if (request.authMode() == AuthMode.ANTHROPIC) {
            builder.header("x-api-key", request.apiKey());
            builder.header("anthropic-version", "2023-06-01");
        } else if (request.authMode() == AuthMode.GOOGLE) {
            builder.header("x-goog-api-key", request.apiKey());
        } else {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }
        return builder.build();
    }

    private List<DiscoveryDocument> parseDocuments(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<DiscoveryDocument> documents = new ArrayList<>();

            JsonNode dataArray = root.path("data");
            if (dataArray.isArray()) {
                dataArray.forEach(node -> documents.add(new DiscoveryDocument(
                        DocumentKind.OPENAI_LIKE,
                        text(node, "id"),
                        firstNonBlank(text(node, "display_name"), text(node, "name")),
                        text(node, "owned_by"),
                        text(node, "provider"),
                        text(node, "type"),
                        readStringArray(node.path("architecture").path("input_modalities")),
                        text(node.path("architecture"), "modality"),
                        readStringArray(node.path("supported_parameters")),
                        readInt(node.path("context_length")),
                        readInt(node.path("top_provider").path("context_length")),
                        null,
                        List.of())));
            }

            JsonNode modelsArray = root.path("models");
            if (modelsArray.isArray()) {
                modelsArray.forEach(node -> documents.add(new DiscoveryDocument(
                        DocumentKind.GEMINI,
                        text(node, "name"),
                        text(node, "displayName"),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        text(node, "publisher"),
                        readStringArray(node.path("supportedGenerationMethods")))));
            }
            return documents;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse model discovery response", exception);
        }
    }

    private int readInt(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : 0;
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(valueNode -> {
            String value = valueNode.asText();
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode valueNode = node.path(fieldName);
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        String value = valueNode.asText();
        return value != null && !value.isBlank() ? value : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
