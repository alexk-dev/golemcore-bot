package me.golemcore.bot.application.models;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.model.catalog.ModelReasoningLevel;
import me.golemcore.bot.domain.model.catalog.ModelReasoningProfile;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.ProviderModelDiscoveryPort;

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
    private static final int DEFAULT_MAX_INPUT_TOKENS = 128000;
    private static final Map<String, List<Integer>> GPT_REASONING_TOKEN_LEVELS = Map.of(
            "gpt-5", List.of(1000000, 1000000, 500000, 250000),
            "gpt-5.1", List.of(1000000, 1000000, 500000, 250000),
            "gpt-5.2", List.of(1000000, 1000000, 500000, 250000));

    private final RuntimeConfigService runtimeConfigService;
    private final ProviderModelDiscoveryPort providerModelDiscoveryPort;

    public List<DiscoveredModel> discoverModels(String providerName) {
        String normalizedProvider = normalizeProviderName(providerName);
        RuntimeConfig.LlmProviderConfig providerConfig = requireConfiguredProvider(normalizedProvider);
        String apiKey = Secret.valueOrEmpty(providerConfig.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider '" + normalizedProvider + "' does not have an API key configured");
        }

        ProviderModelDiscoveryPort.DiscoveryRequest request = buildDiscoveryRequest(providerConfig, apiKey);
        ProviderModelDiscoveryPort.DiscoveryResponse response = sendDiscoveryRequest(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Model discovery failed for provider '" + normalizedProvider
                    + "' with status " + response.statusCode());
        }

        List<DiscoveredModel> models = parseModels(normalizedProvider, providerConfig, response.documents());
        log.info("[ModelDiscovery] Discovered {} models for provider {}", models.size(), normalizedProvider);
        return models;
    }

    protected ProviderModelDiscoveryPort.DiscoveryResponse sendDiscoveryRequest(
            ProviderModelDiscoveryPort.DiscoveryRequest request) {
        return providerModelDiscoveryPort.discover(request);
    }

    protected ProviderModelDiscoveryPort.DiscoveryRequest buildDiscoveryRequest(
            RuntimeConfig.LlmProviderConfig providerConfig,
            String apiKey) {
        String apiType = getApiType(providerConfig);
        return new ProviderModelDiscoveryPort.DiscoveryRequest(
                buildDiscoveryUri(providerConfig, apiType),
                resolveTimeout(providerConfig),
                apiKey,
                USER_AGENT,
                resolveAuthMode(apiType));
    }

    protected ProviderModelDiscoveryPort.AuthMode resolveAuthMode(String apiType) {
        if (API_TYPE_ANTHROPIC.equals(apiType)) {
            return ProviderModelDiscoveryPort.AuthMode.ANTHROPIC;
        }
        if (API_TYPE_GEMINI.equals(apiType)) {
            return ProviderModelDiscoveryPort.AuthMode.GOOGLE;
        }
        return ProviderModelDiscoveryPort.AuthMode.BEARER;
    }

    public record DiscoveredModel(String provider, String id, String displayName, String ownedBy,
            ModelCatalogEntry defaultCatalogEntry) {

        public ModelCatalogEntry defaultSettings() {
            return defaultCatalogEntry;
        }
    }

    private RuntimeConfig.LlmProviderConfig requireConfiguredProvider(String providerName) {
        List<String> configuredProviders = runtimeConfigService.getConfiguredLlmProviders();
        if (!configuredProviders.contains(providerName)) {
            throw new IllegalArgumentException("Provider '" + providerName + "' is not configured");
        }
        return runtimeConfigService.getLlmProviderConfig(providerName);
    }

    private Duration resolveTimeout(RuntimeConfig.LlmProviderConfig providerConfig) {
        Integer timeoutSeconds = providerConfig.getRequestTimeoutSeconds();
        return Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 30);
    }

    private URI buildDiscoveryUri(RuntimeConfig.LlmProviderConfig providerConfig, String apiType) {
        String baseUrl = providerConfig.getBaseUrl();
        String resolvedBaseUrl = resolveBaseUrl(baseUrl, apiType);
        return appendPath(URI.create(resolvedBaseUrl), "/models");
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
            List<ProviderModelDiscoveryPort.DiscoveryDocument> documents) {
        List<DiscoveredModel> models = new ArrayList<>();
        boolean attachDirectDefaults = shouldAttachOpenRouterDefaults(providerName, providerConfig);
        for (ProviderModelDiscoveryPort.DiscoveryDocument document : documents) {
            if (document.kind() == ProviderModelDiscoveryPort.DocumentKind.GEMINI) {
                addGeminiModel(models, providerName, document);
            } else {
                addOpenAiLikeModel(models, providerName, document, attachDirectDefaults);
            }
        }
        return models.stream()
                .filter(model -> model.id() != null && !model.id().isBlank())
                .sorted(Comparator.comparing(DiscoveredModel::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
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

    private void addOpenAiLikeModel(List<DiscoveredModel> models, String providerName,
            ProviderModelDiscoveryPort.DiscoveryDocument document,
            boolean attachDirectDefaults) {
        String id = document.id();
        if (id == null || id.isBlank()) {
            return;
        }

        String displayName = firstNonBlank(
                document.displayName(),
                id);
        String ownedBy = firstNonBlank(
                document.ownedBy(),
                document.provider(),
                document.type());
        ModelCatalogEntry defaultSettings = attachDirectDefaults
                ? buildOpenRouterDefaultSettings(providerName, id, document, displayName)
                : null;
        models.add(new DiscoveredModel(providerName, id, displayName, ownedBy, defaultSettings));
    }

    private ModelCatalogEntry buildOpenRouterDefaultSettings(String providerName, String modelId,
            ProviderModelDiscoveryPort.DiscoveryDocument document, String displayName) {
        int maxInputTokens = resolveMaxInputTokens(document);
        return new ModelCatalogEntry(
                providerName,
                displayName,
                supportsVision(document),
                supportsTemperature(document.supportedParameters()),
                maxInputTokens,
                buildReasoningConfig(modelId, maxInputTokens));
    }

    private boolean supportsVision(ProviderModelDiscoveryPort.DiscoveryDocument document) {
        if (containsValue(document.inputModalities(), "image") || containsValue(document.inputModalities(), "video")) {
            return true;
        }
        String modality = document.modality();
        if (modality == null) {
            return false;
        }
        String normalizedModality = modality.toLowerCase(Locale.ROOT);
        return normalizedModality.contains("image") || normalizedModality.contains("video");
    }

    private boolean supportsTemperature(List<String> supportedParameters) {
        if (supportedParameters == null || supportedParameters.isEmpty()) {
            return true;
        }
        return containsValue(supportedParameters, "temperature");
    }

    private int resolveMaxInputTokens(ProviderModelDiscoveryPort.DiscoveryDocument document) {
        int contextLength = document.contextLength() != null ? document.contextLength() : 0;
        if (contextLength > 0) {
            return contextLength;
        }
        int topProviderContextLength = document.topProviderContextLength() != null
                ? document.topProviderContextLength()
                : 0;
        if (topProviderContextLength > 0) {
            return topProviderContextLength;
        }
        return DEFAULT_MAX_INPUT_TOKENS;
    }

    private ModelReasoningProfile buildReasoningConfig(String modelId, int maxInputTokens) {
        List<Integer> levelCaps = GPT_REASONING_TOKEN_LEVELS.get(normalizeReasoningModelId(modelId));
        if (levelCaps == null) {
            return null;
        }
        return new ModelReasoningProfile(
                "medium",
                Map.of(
                        "low", new ModelReasoningLevel(levelCaps.get(0)),
                        "medium", new ModelReasoningLevel(levelCaps.get(1)),
                        "high", new ModelReasoningLevel(Math.min(levelCaps.get(2), maxInputTokens)),
                        "xhigh", new ModelReasoningLevel(Math.min(levelCaps.get(3), maxInputTokens))));
    }

    private String normalizeReasoningModelId(String modelId) {
        int separatorIndex = modelId.indexOf('/');
        return separatorIndex >= 0 ? modelId.substring(separatorIndex + 1) : modelId;
    }

    private boolean containsValue(List<String> values, String expectedValue) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (expectedValue.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private void addGeminiModel(List<DiscoveredModel> models, String providerName,
            ProviderModelDiscoveryPort.DiscoveryDocument document) {
        if (!supportsGenerateContent(document.supportedGenerationMethods())) {
            return;
        }

        String rawName = document.id();
        String id = rawName != null && rawName.startsWith("models/")
                ? rawName.substring("models/".length())
                : rawName;
        if (id == null || id.isBlank()) {
            return;
        }

        String displayName = firstNonBlank(document.displayName(), id);
        String ownedBy = firstNonBlank(document.publisher(), "google");
        models.add(new DiscoveredModel(providerName, id, displayName, ownedBy, null));
    }

    private boolean supportsGenerateContent(List<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return true;
        }
        for (String method : methods) {
            if ("generateContent".equals(method)) {
                return true;
            }
        }
        return false;
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
