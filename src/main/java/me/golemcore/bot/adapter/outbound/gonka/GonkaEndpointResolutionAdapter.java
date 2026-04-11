package me.golemcore.bot.adapter.outbound.gonka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GonkaEndpointResolutionAdapter implements GonkaEndpointResolver {

    private static final String CHAIN_PARAMS_PATH = "/chain-api/productscience/inference/inference/params";
    private static final String CURRENT_PARTICIPANTS_PATH = "/v1/epochs/current/participants";
    private static final String IDENTITY_PATH = "/v1/identity";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public GonkaResolvedEndpoint resolve(GonkaEndpointResolutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Gonka endpoint resolution request is required");
        }
        if (request.configuredEndpoints() != null && !request.configuredEndpoints().isEmpty()) {
            return toResolvedEndpoint(request.configuredEndpoints().getFirst());
        }
        if (request.sourceUri() == null) {
            throw new IllegalArgumentException("Gonka provider requires sourceUrl or endpoints");
        }
        Duration timeout = resolveTimeout(request.timeout());
        List<GonkaResolvedEndpoint> endpoints = discoverTransferAgentEndpoints(request.sourceUri(), timeout);
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("No Gonka endpoints discovered from sourceUrl: " + request.sourceUri());
        }
        return resolveDelegateEndpointOrDefault(endpoints.getFirst(), timeout);
    }

    protected HttpClient buildHttpClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    private Duration resolveTimeout(Duration timeout) {
        return timeout != null ? timeout : Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
    }

    private GonkaResolvedEndpoint toResolvedEndpoint(GonkaConfiguredEndpoint endpoint) {
        if (endpoint == null || isBlank(endpoint.url()) || isBlank(endpoint.transferAddress())) {
            throw new IllegalArgumentException("Gonka endpoints must include url and transferAddress");
        }
        return new GonkaResolvedEndpoint(ensureV1(endpoint.url()), endpoint.transferAddress().trim());
    }

    private List<GonkaResolvedEndpoint> discoverTransferAgentEndpoints(URI sourceUri, Duration timeout) {
        Set<String> allowedAddresses = fetchAllowedTransferAddresses(sourceUri, timeout);
        List<GonkaResolvedEndpoint> participantEndpoints = fetchParticipants(sourceUri, timeout);
        if (allowedAddresses.isEmpty()) {
            return participantEndpoints;
        }
        return participantEndpoints.stream()
                .filter(endpoint -> allowedAddresses.contains(endpoint.transferAddress()))
                .toList();
    }

    private Set<String> fetchAllowedTransferAddresses(URI sourceUri, Duration timeout) {
        JsonNode root = getJson(resolveBaseUri(sourceUri).resolve(CHAIN_PARAMS_PATH), timeout);
        JsonNode addressesNode = root.path("params")
                .path("transfer_agent_access_params")
                .path("allowed_transfer_addresses");
        Set<String> addresses = new LinkedHashSet<>();
        if (addressesNode.isArray()) {
            for (JsonNode addressNode : addressesNode) {
                String address = textValue(addressNode);
                if (!isBlank(address)) {
                    addresses.add(address);
                }
            }
        }
        return addresses;
    }

    private List<GonkaResolvedEndpoint> fetchParticipants(URI sourceUri, Duration timeout) {
        JsonNode root = getJson(resolveBaseUri(sourceUri).resolve(CURRENT_PARTICIPANTS_PATH), timeout);
        Set<String> excluded = readExcludedParticipants(root.path("excluded_participants"));
        JsonNode participantsNode = root.path("active_participants").path("participants");
        List<GonkaResolvedEndpoint> endpoints = new ArrayList<>();
        if (!participantsNode.isArray()) {
            return endpoints;
        }
        for (JsonNode participantNode : participantsNode) {
            String inferenceUrl = textValue(participantNode.path("inference_url"));
            String address = textValue(participantNode.path("index"));
            if (!isBlank(inferenceUrl) && !isBlank(address) && !excluded.contains(address)) {
                endpoints.add(new GonkaResolvedEndpoint(ensureV1(inferenceUrl), address));
            }
        }
        return endpoints;
    }

    private Set<String> readExcludedParticipants(JsonNode excludedParticipantsNode) {
        Set<String> excluded = new LinkedHashSet<>();
        if (!excludedParticipantsNode.isArray()) {
            return excluded;
        }
        for (JsonNode excludedNode : excludedParticipantsNode) {
            String address = textValue(excludedNode.path("address"));
            if (!isBlank(address)) {
                excluded.add(address);
            }
        }
        return excluded;
    }

    private GonkaResolvedEndpoint resolveDelegateEndpointOrDefault(GonkaResolvedEndpoint selectedEndpoint,
            Duration timeout) {
        try {
            JsonNode root = getJson(resolveBaseUri(URI.create(selectedEndpoint.url())).resolve(IDENTITY_PATH), timeout);
            JsonNode delegateTa = root.path("data").path("delegate_ta");
            if (!delegateTa.isObject() || delegateTa.isEmpty()) {
                return selectedEndpoint;
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = delegateTa.fields();
            if (!fields.hasNext()) {
                return selectedEndpoint;
            }
            String delegateUrl = fields.next().getKey();
            return new GonkaResolvedEndpoint(ensureV1(delegateUrl), selectedEndpoint.transferAddress());
        } catch (RuntimeException exception) { // NOSONAR - delegate endpoint discovery is optional.
            return selectedEndpoint;
        }
    }

    private JsonNode getJson(URI uri, Duration timeout) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = buildHttpClient(timeout).send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gonka endpoint discovery request failed with status "
                        + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gonka endpoint discovery request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Gonka endpoint discovery request failed: " + exception.getMessage(),
                    exception);
        }
    }

    private URI resolveBaseUri(URI uri) {
        String value = uri.toString().trim();
        if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - 3);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value + "/");
    }

    private String ensureV1(String url) {
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
