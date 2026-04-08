package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderModelDiscoveryService {

    private static final String API_TYPE_OPENAI = "openai";
    private static final String API_TYPE_ANTHROPIC = "anthropic";
    private static final String API_TYPE_GEMINI = "gemini";
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";
    private static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String OPENROUTER_PROVIDER_NAME = "openrouter";
    private static final String USER_AGENT = "golemcore-model-discovery";
    private static final String GEMINI_GENERATE_CONTENT_METHOD = "generateContent";
    private static final int DEFAULT_MAX_INPUT_TOKENS = 128000;
    private static final Map<String, List<Integer>> GPT_REASONING_TOKEN_LEVELS = Map.of(
            "gpt-5", List.of(1000000, 1000000, 500000, 250000),
            "gpt-5.1", List.of(1000000, 1000000, 500000, 250000),
            "gpt-5.2", List.of(1000000, 1000000, 500000, 250000));

    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DiscoveredModel> discoverModels(String providerName) {
        return discoverModelsForProvider(providerName).models();
    }

    public DiscoveryResult discoverModelsForProvider(String providerName) {
        String normalizedProvider = normalizeProviderName(providerName);
        RuntimeConfig.LlmProviderConfig providerConfig = requireConfiguredProvider(normalizedProvider);
        return discoverModelsForConfig(normalizedProvider, providerConfig);
    }

    public DiscoveryResult discoverModelsForConfig(String providerName,
            RuntimeConfig.LlmProviderConfig providerConfig) {
        String normalizedProvider = normalizeProviderName(providerName);
        if (providerConfig == null) {
            throw new IllegalArgumentException("Provider config is required");
        }
        String apiKey = Secret.valueOrEmpty(providerConfig.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider '" + normalizedProvider + "' does not have an API key configured");
        }

        String apiType = getApiType(providerConfig);
        URI discoveryUri = buildDiscoveryUri(providerConfig, apiType);
        HttpRequest request = buildDiscoveryRequest(discoveryUri, providerConfig, apiType, apiKey);
        DiscoveryResponse response = sendDiscoveryRequest(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Model discovery failed for provider '" + normalizedProvider
                    + "' with status " + response.statusCode());
        }

        List<DiscoveredModel> models = parseModels(normalizedProvider, providerConfig, response.body());
        log.info("[ModelDiscovery] Discovered {} models for provider {}", models.size(), normalizedProvider);
        return new DiscoveryResult(discoveryUri.toString(), models);
    }

    protected DiscoveryResponse sendDiscoveryRequest(HttpRequest request) {
        try {
            HttpResponse<String> response = buildHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new DiscoveryResponse(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model discovery request was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Model discovery request failed: " + e.getMessage(), e);
        }
    }

    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    protected record DiscoveryResponse(int statusCode, String body) {
    }

    public record DiscoveryResult(String resolvedEndpoint, List<DiscoveredModel> models) {
    }

    public record DiscoveredModel(String provider, String id, String displayName, String ownedBy,
            ModelConfigService.ModelSettings defaultSettings) {
    }

    private RuntimeConfig.LlmProviderConfig requireConfiguredProvider(String providerName) {
        List<String> configuredProviders = runtimeConfigService.getConfiguredLlmProviders();
        if (!configuredProviders.contains(providerName)) {
            throw new IllegalArgumentException("Provider '" + providerName + "' is not configured");
        }
        return runtimeConfigService.getLlmProviderConfig(providerName);
    }

    private HttpRequest buildDiscoveryRequest(URI discoveryUri, RuntimeConfig.LlmProviderConfig providerConfig,
            String apiType, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(discoveryUri)
                .timeout(resolveTimeout(providerConfig))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET();

        if (API_TYPE_ANTHROPIC.equals(apiType)) {
            builder.header("x-api-key", apiKey);
            builder.header("anthropic-version", "2023-06-01");
        } else if (API_TYPE_GEMINI.equals(apiType)) {
            builder.header("x-goog-api-key", apiKey);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    private Duration resolveTimeout(RuntimeConfig.LlmProviderConfig providerConfig) {
        Integer timeoutSeconds = providerConfig.getRequestTimeoutSeconds();
        return Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 30);
    }

    private URI buildDiscoveryUri(RuntimeConfig.LlmProviderConfig providerConfig, String apiType) {
        String baseUrl = providerConfig.getBaseUrl();
        String resolvedBaseUrl = resolveBaseUrl(baseUrl, apiType);
        return requireSafeDiscoveryUri(appendPath(URI.create(resolvedBaseUrl), "/models"));
    }

    private String resolveBaseUrl(String baseUrl, String apiType) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return defaultBaseUrl(apiType);
        }

        URI baseUri = URI.create(baseUrl.trim());
        String path = baseUri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return appendPath(baseUri, defaultVersionPath(apiType)).toString();
        }
        return baseUrl.trim();
    }

    private String defaultBaseUrl(String apiType) {
        if (API_TYPE_ANTHROPIC.equals(apiType)) {
            return DEFAULT_ANTHROPIC_BASE_URL;
        }
        if (API_TYPE_GEMINI.equals(apiType)) {
            return DEFAULT_GEMINI_BASE_URL;
        }
        return DEFAULT_OPENAI_BASE_URL;
    }

    private String defaultVersionPath(String apiType) {
        return API_TYPE_GEMINI.equals(apiType) ? "/v1beta" : "/v1";
    }

    private URI appendPath(URI baseUri, String suffix) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String normalizedSuffix = suffix.startsWith("/") ? suffix : "/" + suffix;
        return URI.create(base + normalizedSuffix);
    }

    private URI requireSafeDiscoveryUri(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Provider base URL is required");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Provider base URL must use http or https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Provider base URL must not include user info");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Provider base URL must not include a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Provider base URL host is required");
        }
        if (isLocalOrPrivateHost(host)) {
            throw new IllegalArgumentException("Provider base URL must resolve to a public host");
        }
        return uri;
    }

    private boolean isLocalOrPrivateHost(String host) {
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost)) {
            return true;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return true;
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || isUniqueLocalIpv6(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException ignored) {
            return true;
        }
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address inet6Address)) {
            return false;
        }
        byte[] bytes = inet6Address.getAddress();
        return bytes.length > 0 && (bytes[0] & (byte) 0xfe) == (byte) 0xfc;
    }

    private String getApiType(RuntimeConfig.LlmProviderConfig config) {
        String apiType = config.getApiType();
        if (apiType == null || apiType.isBlank()) {
            return API_TYPE_OPENAI;
        }
        return apiType.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeProviderName(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("Provider name is required");
        }
        return providerName.trim().toLowerCase(Locale.ROOT);
    }

    private List<DiscoveredModel> parseModels(String providerName, RuntimeConfig.LlmProviderConfig providerConfig,
            String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<DiscoveredModel> models = new ArrayList<>();
            boolean attachDirectDefaults = shouldAttachOpenRouterDefaults(providerName, providerConfig);
            JsonNode dataArray = root.path("data");
            if (dataArray.isArray()) {
                dataArray.forEach(node -> addOpenAiLikeModel(models, providerName, node, attachDirectDefaults));
            }

            JsonNode geminiModels = root.path("models");
            if (geminiModels.isArray()) {
                geminiModels.forEach(node -> addGeminiModel(models, providerName, node));
            }

            return models.stream()
                    .filter(model -> model.id() != null && !model.id().isBlank())
                    .sorted(Comparator.comparing(DiscoveredModel::displayName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse model discovery response", e);
        }
    }

    private boolean shouldAttachOpenRouterDefaults(String providerName,
            RuntimeConfig.LlmProviderConfig providerConfig) {
        if (OPENROUTER_PROVIDER_NAME.equals(providerName)) {
            return true;
        }
        try {
            String resolvedBaseUrl = resolveBaseUrl(providerConfig.getBaseUrl(), getApiType(providerConfig));
            URI baseUri = URI.create(resolvedBaseUrl);
            return OPENROUTER_HOST.equalsIgnoreCase(baseUri.getHost());
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private void addOpenAiLikeModel(List<DiscoveredModel> models, String providerName, JsonNode node,
            boolean attachDirectDefaults) {
        String id = text(node, "id");
        if (id == null || id.isBlank()) {
            return;
        }

        String displayName = firstNonBlank(
                text(node, "display_name"),
                text(node, "name"),
                id);
        String ownedBy = firstNonBlank(
                text(node, "owned_by"),
                text(node, "provider"),
                text(node, "type"));
        ModelConfigService.ModelSettings defaultSettings = attachDirectDefaults
                ? buildOpenRouterDefaultSettings(providerName, id, node, displayName)
                : null;
        models.add(new DiscoveredModel(providerName, id, displayName, ownedBy, defaultSettings));
    }

    private ModelConfigService.ModelSettings buildOpenRouterDefaultSettings(String providerName, String modelId,
            JsonNode node, String displayName) {
        ModelConfigService.ModelSettings settings = new ModelConfigService.ModelSettings();
        settings.setProvider(providerName);
        settings.setDisplayName(displayName);
        settings.setSupportsVision(supportsVision(node));
        settings.setSupportsTemperature(supportsTemperature(node.path("supported_parameters")));
        settings.setMaxInputTokens(resolveMaxInputTokens(node));
        settings.setReasoning(buildReasoningConfig(modelId, settings.getMaxInputTokens()));
        return settings;
    }

    private boolean supportsVision(JsonNode node) {
        JsonNode inputModalities = node.path("architecture").path("input_modalities");
        if (containsArrayValue(inputModalities, "image") || containsArrayValue(inputModalities, "video")) {
            return true;
        }
        String modality = text(node.path("architecture"), "modality");
        if (modality == null) {
            return false;
        }
        String normalizedModality = modality.toLowerCase(Locale.ROOT);
        return normalizedModality.contains("image") || normalizedModality.contains("video");
    }

    private boolean supportsTemperature(JsonNode supportedParameters) {
        if (!supportedParameters.isArray()) {
            return true;
        }
        return containsArrayValue(supportedParameters, "temperature");
    }

    private int resolveMaxInputTokens(JsonNode node) {
        int contextLength = node.path("context_length").asInt(0);
        if (contextLength > 0) {
            return contextLength;
        }
        int topProviderContextLength = node.path("top_provider").path("context_length").asInt(0);
        if (topProviderContextLength > 0) {
            return topProviderContextLength;
        }
        return DEFAULT_MAX_INPUT_TOKENS;
    }

    private ModelConfigService.ReasoningConfig buildReasoningConfig(String modelId, int maxInputTokens) {
        List<Integer> levelCaps = GPT_REASONING_TOKEN_LEVELS.get(normalizeReasoningModelId(modelId));
        if (levelCaps == null) {
            return null;
        }
        ModelConfigService.ReasoningConfig reasoningConfig = new ModelConfigService.ReasoningConfig();
        reasoningConfig.setDefaultLevel("medium");
        reasoningConfig.setLevels(Map.of(
                "low", new ModelConfigService.ReasoningLevelConfig(levelCaps.get(0)),
                "medium", new ModelConfigService.ReasoningLevelConfig(levelCaps.get(1)),
                "high", new ModelConfigService.ReasoningLevelConfig(Math.min(levelCaps.get(2), maxInputTokens)),
                "xhigh", new ModelConfigService.ReasoningLevelConfig(Math.min(levelCaps.get(3), maxInputTokens))));
        return reasoningConfig;
    }

    private String normalizeReasoningModelId(String modelId) {
        int separatorIndex = modelId.indexOf('/');
        return separatorIndex >= 0 ? modelId.substring(separatorIndex + 1) : modelId;
    }

    private boolean containsArrayValue(JsonNode values, String expectedValue) {
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode valueNode : values) {
            String value = valueNode.asText();
            if (expectedValue.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private void addGeminiModel(List<DiscoveredModel> models, String providerName, JsonNode node) {
        if (!supportsGenerateContent(node.path("supportedGenerationMethods"))) {
            return;
        }

        String rawName = text(node, "name");
        String id = rawName != null && rawName.startsWith("models/")
                ? rawName.substring("models/".length())
                : rawName;
        if (id == null || id.isBlank()) {
            return;
        }

        String displayName = firstNonBlank(text(node, "displayName"), id);
        String ownedBy = firstNonBlank(text(node, "publisher"), "google");
        models.add(new DiscoveredModel(providerName, id, displayName, ownedBy, null));
    }

    private boolean supportsGenerateContent(JsonNode methodsNode) {
        if (!methodsNode.isArray()) {
            return true;
        }
        for (JsonNode methodNode : methodsNode) {
            if (GEMINI_GENERATE_CONTENT_METHOD.equals(methodNode.asText())) {
                return true;
            }
        }
        return false;
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
