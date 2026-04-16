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

package me.golemcore.bot.adapter.outbound.llm;

import me.golemcore.bot.domain.component.LlmComponent;
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmProviderMetadataKeys;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.catalog.ModelCatalogEntry;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.system.LlmErrorPatterns;
import me.golemcore.bot.port.outbound.ModelConfigPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.ToolArtifactReadPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * LLM adapter using the langchain4j library.
 *
 * <p>
 * This adapter provides integration with multiple LLM providers through the
 * langchain4j library, supporting:
 * <ul>
 * <li>OpenAI (including reasoning models like o1, o3)
 * <li>Anthropic (Claude models)
 * <li>Any OpenAI-compatible API endpoint
 * </ul>
 *
 * <p>
 * Features:
 * <ul>
 * <li>Model routing by tier (fast/default/smart/coding)
 * <li>Function calling (tool use) support
 * <li>Reasoning effort control for o-series models
 * <li>Automatic retry with exponential backoff for rate limits
 * <li>Model-specific capability detection (temperature, reasoning)
 * </ul>
 *
 * <p>
 * Provider ID: {@code "langchain4j"}
 *
 * <p>
 * Configuration via {@code bot.llm.langchain4j.providers.*} and
 * {@code models.json} for model capabilities.
 *
 * @see LlmProviderAdapter
 * @see me.golemcore.bot.infrastructure.config.ModelConfigService
 */
@Component
@Slf4j
public class Langchain4jAdapter implements LlmProviderAdapter, LlmComponent {

    /**
     * Max retry attempts for rate limit / transient errors (exponential backoff).
     */
    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 5_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final String API_TYPE_OPENAI = "openai";
    private static final String API_TYPE_ANTHROPIC = "anthropic";
    private static final String API_TYPE_GEMINI = "gemini";
    private static final java.util.regex.Pattern RESET_SECONDS_PATTERN = java.util.regex.Pattern
            .compile("\"reset_seconds\"\\s*:\\s*(\\d+)");
    private static final Set<String> RATE_LIMIT_MARKERS = Set.of(
            "rate_limit",
            "rate limit",
            "token_quota_exceeded",
            "too many requests",
            "model_cooldown",
            "cooling down");

    private final RuntimeConfigService runtimeConfigService;
    private final ModelConfigPort modelConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Langchain4jMessageConverter messageConverter;
    private final Langchain4jToolSchemaConverter toolSchemaConverter;
    private final Langchain4jResponseMapper responseMapper;

    private ChatModel chatModel;
    private String currentModel;
    private volatile boolean initialized = false;
    private final Map<String, StreamingChatModel> responsesStreamingModels = new java.util.concurrent.ConcurrentHashMap<>();

    public Langchain4jAdapter(RuntimeConfigService runtimeConfigService, ModelConfigPort modelConfig,
            ToolArtifactReadPort toolArtifactReadPort) {
        this.runtimeConfigService = runtimeConfigService;
        this.modelConfig = modelConfig;
        this.messageConverter = new Langchain4jMessageConverter(toolArtifactReadPort, objectMapper);
        this.toolSchemaConverter = new Langchain4jToolSchemaConverter();
        this.responseMapper = new Langchain4jResponseMapper(objectMapper);
    }

    @Override
    public synchronized void initialize() {
        if (initialized)
            return;

        // Use balanced model from router config
        String model = runtimeConfigService.getBalancedModel();
        String reasoning = runtimeConfigService.getBalancedModelReasoning();
        this.currentModel = model;

        if (model == null || model.isBlank()) {
            initialized = true;
            log.info("Langchain4j adapter initialized without a default model");
            return;
        }

        try {
            this.chatModel = createModel(model, reasoning, "balanced");
            initialized = true;
            log.info("Langchain4j adapter initialized with model: {}, reasoning: {}", model, reasoning);
        } catch (Exception e) {
            log.warn("Failed to initialize Langchain4j adapter: {}", e.getMessage());
        }
    }

    private String getProvider(String model) {
        return modelConfig.getProvider(model);
    }

    private boolean isReasoningRequired(String model) {
        return modelConfig.isReasoningRequired(model);
    }

    private boolean supportsTemperature(String model) {
        return modelConfig.supportsTemperature(model);
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private RuntimeConfig.LlmProviderConfig getProviderConfig(String providerName) {
        RuntimeConfig.LlmProviderConfig config = runtimeConfigService.getLlmProviderConfig(providerName);
        if (config == null) {
            throw new IllegalStateException("Provider not configured in runtime config: " + providerName);
        }
        return config;
    }

    private boolean isResponsesApiRequest(LlmRequest request) {
        String model = request.getModel() != null ? request.getModel() : currentModel;
        if (model == null) {
            return false;
        }
        String provider = getProvider(model);
        RuntimeConfig.LlmProviderConfig config = getProviderConfig(provider);
        String apiType = getApiType(config);
        return API_TYPE_OPENAI.equals(apiType) && !Boolean.TRUE.equals(config.getLegacyApi());
    }

    private StreamingChatModel getResponsesStreamingModel(String model, String reasoningEffort, String modelTier) {
        double temperature = runtimeConfigService.getTemperatureForModel(modelTier, model);
        String cacheKey = model + ":" + (reasoningEffort != null ? reasoningEffort : "") + ":" + temperature;
        return responsesStreamingModels.computeIfAbsent(cacheKey, key -> {
            String provider = getProvider(model);
            RuntimeConfig.LlmProviderConfig config = getProviderConfig(provider);
            String modelName = stripProviderPrefix(model);
            log.info("[LLM] Creating OpenAI Responses streaming model for: {} (reasoning: {})",
                    modelName, reasoningEffort);
            return createResponsesStreamingModel(model, modelName, reasoningEffort, config, temperature);
        });
    }

    private StreamingChatModel createResponsesStreamingModel(String fullModel, String modelName, String reasoningEffort,
            RuntimeConfig.LlmProviderConfig config, double temperature) {
        String apiKey = Secret.valueOrEmpty(config.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for OpenAI Responses provider in runtime config");
        }
        Duration timeout = Duration.ofSeconds(
                config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300);
        OpenAiResponsesStreamingChatModel.Builder builder = OpenAiResponsesStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(true)
                .logResponses(true);

        builder.httpClientBuilder(createResponsesCompatibilityHttpClientBuilder(timeout));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.reasoningEffort(reasoningEffort);
        }

        if (supportsTemperature(fullModel)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private HttpClientBuilder createResponsesCompatibilityHttpClientBuilder(Duration timeout) {
        HttpClientBuilder baseBuilder = HttpClientBuilderLoader.loadHttpClientBuilder();
        if (baseBuilder == null) {
            baseBuilder = instantiateJdkHttpClientBuilder();
        }
        baseBuilder.connectTimeout(timeout);
        baseBuilder.readTimeout(timeout);
        return new ResponsesCompatibilityHttpClientBuilder(baseBuilder, objectMapper);
    }

    private HttpClientBuilder instantiateJdkHttpClientBuilder() {
        try {
            Class<?> builderClass = Class.forName("dev.langchain4j.http.client.jdk.JdkHttpClientBuilder");
            return (HttpClientBuilder) builderClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException(
                    "No HttpClientBuilder implementation available for OpenAI Responses compatibility layer",
                    exception);
        }
    }

    private String stripProviderPrefix(String model) {
        if (model == null) {
            return null;
        }
        return model.contains("/") ? model.substring(model.indexOf('/') + 1) : model;
    }

    /**
     * Create a model instance based on configuration.
     */
    private ChatModel createModel(String model, String reasoningEffort) {
        return createModel(model, reasoningEffort, null);
    }

    private ChatModel createModel(String model, String reasoningEffort, String modelTier) {
        String provider = getProvider(model);
        RuntimeConfig.LlmProviderConfig config = getProviderConfig(provider);
        String modelName = stripProviderPrefix(model);
        String apiType = getApiType(config);
        double temperature = runtimeConfigService.getTemperatureForModel(modelTier, model);

        switch (apiType) {
        case API_TYPE_ANTHROPIC:
            return createAnthropicModel(model, modelName, config, temperature);
        case API_TYPE_GEMINI:
            return createGeminiModel(model, modelName, config, temperature);
        default:
            return createOpenAiModel(modelName, model, reasoningEffort, config, temperature);
        }
    }

    private String getApiType(RuntimeConfig.LlmProviderConfig config) {
        String apiType = config.getApiType();
        if (apiType == null || apiType.isBlank()) {
            return API_TYPE_OPENAI;
        }
        return apiType.trim().toLowerCase(Locale.ROOT);
    }

    private ChatModel createAnthropicModel(String fullModel, String modelName, RuntimeConfig.LlmProviderConfig config,
            double temperature) {
        String apiKey = Secret.valueOrEmpty(config.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for provider anthropic in runtime config");
        }
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0) // Retry handled by our backoff logic
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(
                        config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (supportsTemperature(fullModel)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private ChatModel createGeminiModel(String fullModel, String modelName, RuntimeConfig.LlmProviderConfig config,
            double temperature) {
        String apiKey = Secret.valueOrEmpty(config.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for Gemini provider in runtime config");
        }
        GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0)
                // langchain4j only exposes and rehydrates Gemini thought signatures when
                // both returnThinking and sendThinking are enabled.
                .returnThinking(true)
                .sendThinking(true)
                .timeout(Duration.ofSeconds(
                        config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300));

        if (supportsTemperature(fullModel)) {
            builder.temperature(temperature);
        }

        return builder.build();
    }

    private ChatModel createOpenAiModel(String modelName, String fullModel,
            String reasoningEffort, RuntimeConfig.LlmProviderConfig config, double temperature) {
        String apiKey = Secret.valueOrEmpty(config.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for provider in runtime config");
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0) // Retry handled by our backoff logic
                .timeout(Duration.ofSeconds(
                        config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (supportsTemperature(fullModel)) {
            builder.temperature(temperature);
        } else {
            log.debug("Using reasoning model: {}, effort: {}", modelName,
                    reasoningEffort != null ? reasoningEffort : "default");
        }

        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            builder.reasoningEffort(reasoningEffort);
        }

        return builder.build();
    }

    @Override
    public String getProviderId() {
        return "langchain4j";
    }

    @Override
    public CompletableFuture<LlmResponse> chat(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();

            boolean useResponsesApi = isResponsesApiRequest(request);
            if (chatModel == null && request.getModel() == null && !useResponsesApi) {
                throw new RuntimeException("Langchain4j adapter not available");
            }

            String effectiveModelId = request.getModel() != null ? request.getModel() : currentModel;
            if (log.isDebugEnabled() && effectiveModelId != null) {
                try {
                    String provider = getProvider(effectiveModelId);
                    RuntimeConfig.LlmProviderConfig providerConfig = getProviderConfig(provider);
                    log.debug("[LLM] API call: provider={}, model={}, baseUrl={}",
                            provider, effectiveModelId, providerConfig.getBaseUrl());
                } catch (Exception e) {
                    log.debug("[LLM] API call: model={} (provider resolution failed: {})",
                            effectiveModelId, e.getMessage());
                }
            }

            ChatModel modelToUse = useResponsesApi ? null : getModelForRequest(request);
            boolean geminiApiType = !useResponsesApi && isGeminiRequest(request);
            LlmRequest requestToUse = request;
            MessageConversionResult conversionResult = buildChatMessages(requestToUse);
            List<ChatMessage> messages = conversionResult.messages();
            List<ToolSpecification> tools = convertTools(request);
            boolean compatibilityFlatteningApplied = false;
            boolean toolAttachmentFallbackApplied = false;

            int attempt = 0;
            while (attempt <= MAX_RETRIES) {
                try {
                    ChatResponse response;
                    if (useResponsesApi) {
                        String effectiveModel = requestToUse.getModel() != null
                                ? requestToUse.getModel()
                                : currentModel;
                        StreamingChatModel streamingModel = getResponsesStreamingModel(
                                effectiveModel, requestToUse.getReasoningEffort(), requestToUse.getModelTier());
                        response = chatViaStreaming(streamingModel, messages, tools);
                    } else if (tools != null && !tools.isEmpty()) {
                        log.trace("Calling LLM with {} tools", tools.size());
                        ChatRequest chatRequest = ChatRequest.builder()
                                .messages(messages)
                                .toolSpecifications(tools)
                                .build();
                        response = modelToUse.chat(chatRequest);
                    } else {
                        response = modelToUse.chat(messages);
                    }

                    LlmResponse llmResponse = convertResponse(response, compatibilityFlatteningApplied, geminiApiType);
                    if (toolAttachmentFallbackApplied) {
                        llmResponse = withProviderMetadata(llmResponse, Map.of(
                                LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_APPLIED, true,
                                LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON,
                                LlmProviderMetadataKeys.TOOL_ATTACHMENT_FALLBACK_REASON_OVERSIZE_INVALID_JSON));
                    }
                    return llmResponse;
                } catch (Exception e) {
                    if (!requestToUse.isDisableToolAttachmentHydration()
                            && conversionResult.hydratedToolImages()
                            && isOversizedToolAttachmentError(e)) {
                        requestToUse = copyWithoutToolAttachmentHydration(requestToUse);
                        conversionResult = buildChatMessages(requestToUse);
                        messages = conversionResult.messages();
                        toolAttachmentFallbackApplied = true;
                        log.warn("[LLM] Provider rejected oversized inline tool attachments; retrying without them");
                        continue;
                    }
                    if (isRateLimitError(e) && attempt < MAX_RETRIES) {
                        long exponentialBackoffMs = (long) (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt));
                        long resetSeconds = extractResetSeconds(e);
                        long backoffMs = resetSeconds > 0
                                ? Math.max(resetSeconds * 1000 + 1000, exponentialBackoffMs)
                                : exponentialBackoffMs;
                        log.warn("[LLM] Rate limit hit for {} (attempt {}/{}), retrying in {}ms{}...",
                                request.getModel(), attempt + 1, MAX_RETRIES, backoffMs,
                                resetSeconds > 0 ? " (server requested " + resetSeconds + "s)" : "");
                        try {
                            sleepBeforeRetry(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("LLM chat interrupted during retry backoff", ie);
                        }
                        attempt++;
                    } else if (!compatibilityFlatteningApplied && isUnsupportedFunctionRoleError(e)) {
                        compatibilityFlatteningApplied = true;
                        messages = convertMessagesWithFlattenedToolHistory(requestToUse);
                        log.warn(
                                "[LLM] Retrying request with flattened tool history due to unsupported function/tool role");
                    } else {
                        log.error("LLM chat failed", e);
                        throw new RuntimeException("LLM chat failed: " + e.getMessage(), e);
                    }
                }
            }
            throw new RuntimeException("LLM chat failed: max retries exhausted");
        });
    }

    private boolean isRateLimitError(Throwable e) {
        // Use identity semantics for the visited set: the intent is "have I seen this
        // exact cause object before" (cycle guard), not value equality. A future
        // provider exception that overrides equals/hashCode - or a wrapped cause that
        // happens to compare equal to an earlier frame - must not collapse into a
        // single visited entry and prematurely terminate the cause walk.
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = e; current != null && visited.add(current); current = current.getCause()) {
            // langchain4j maps HTTP 429 to RateLimitException regardless of body content.
            if (current instanceof dev.langchain4j.exception.RateLimitException) {
                return true;
            }
            String raw = current.getMessage();
            if (raw == null) {
                continue;
            }
            String normalized = raw.toLowerCase(Locale.ROOT);
            // Context-overflow errors sometimes carry rate-limit-adjacent wording
            // ("too many tokens") - they must NOT be retried as rate limits.
            if (LlmErrorPatterns.matchesContextOverflow(normalized)) {
                continue;
            }
            if (containsAny(normalized, RATE_LIMIT_MARKERS) || looksLikeHttp429(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeHttp429(String normalized) {
        // Avoid matching an arbitrary "429" in stack traces, byte counts, or IDs.
        return normalized.contains("http 429")
                || normalized.contains("status 429")
                || normalized.contains("status: 429")
                || normalized.contains("status_code=429")
                || normalized.contains("code 429")
                || normalized.contains("429 too many");
    }

    private static boolean containsAny(String haystack, Set<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract reset_seconds from rate limit error JSON body (e.g. cli-proxy-api
     * cooldown). Returns -1 if not found.
     */
    private long extractResetSeconds(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.contains("reset_seconds")) {
                java.util.regex.Matcher matcher = RESET_SECONDS_PATTERN.matcher(msg);
                if (matcher.find()) {
                    try {
                        return Long.parseLong(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
            current = current.getCause();
        }
        return -1;
    }

    private boolean isOversizedToolAttachmentError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("request body must be valid json")
                        || normalized.contains("payload too large")
                        || normalized.contains("request entity too large")
                        || normalized.contains("body_size_exceeded")
                        || normalized.contains("\"detail\":\"payload too large\"")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private LlmRequest copyWithoutToolAttachmentHydration(LlmRequest request) {
        return LlmRequest.builder()
                .model(request.getModel())
                .systemPrompt(request.getSystemPrompt())
                .messages(request.getMessages())
                .tools(request.getTools())
                .toolResults(request.getToolResults())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .stream(request.isStream())
                .disableToolAttachmentHydration(true)
                .sessionId(request.getSessionId())
                .traceId(request.getTraceId())
                .traceSpanId(request.getTraceSpanId())
                .traceParentSpanId(request.getTraceParentSpanId())
                .traceRootKind(request.getTraceRootKind())
                .modelTier(request.getModelTier())
                .reasoningEffort(request.getReasoningEffort())
                .build();
    }

    private ChatModel getModelForRequest(LlmRequest request) {
        String requestModel = request.getModel();
        String reasoningEffort = request.getReasoningEffort();
        String modelTier = request.getModelTier();
        String effectiveModel = requestModel != null ? requestModel : currentModel;

        if (modelTier != null && effectiveModel != null) {
            log.trace("Creating tier-scoped model for request: {}, tier: {}, reasoning: {}",
                    effectiveModel, modelTier, reasoningEffort);
            return createModel(effectiveModel, reasoningEffort, modelTier);
        }

        // If request specifies a different model, create one-off
        if (requestModel != null && (chatModel == null || !requestModel.equals(currentModel))) {
            log.trace("Creating one-off model for request: {}, reasoning: {}", requestModel, reasoningEffort);
            return createModel(requestModel, reasoningEffort, null);
        }

        // If request specifies different reasoning for current model
        if (currentModel != null && reasoningEffort != null && isReasoningRequired(currentModel)) {
            log.debug("Using reasoning effort: {} for model: {}", reasoningEffort, currentModel);
            return createModel(currentModel, reasoningEffort, null);
        }

        return chatModel;
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        ensureInitialized();

        // Real SSE streaming for OpenAI Responses API (non-legacy)
        if (isResponsesApiRequest(request)) {
            String effectiveModel = request.getModel() != null ? request.getModel() : currentModel;
            StreamingChatModel streamingModel = getResponsesStreamingModel(
                    effectiveModel, request.getReasoningEffort(), request.getModelTier());
            MessageConversionResult conversionResult = buildChatMessages(request);
            List<ChatMessage> messages = conversionResult.messages();
            List<ToolSpecification> tools = convertTools(request);
            return streamViaResponsesApi(streamingModel, messages, tools);
        }

        // Wrapped sync fallback for legacy OpenAI, Anthropic, Gemini
        return Flux.create(sink -> {
            chat(request).whenComplete((response, error) -> {
                if (error != null) {
                    sink.error(error);
                } else {
                    sink.next(LlmChunk.builder()
                            .text(response.getContent())
                            .done(true)
                            .usage(response.getUsage())
                            .build());
                    sink.complete();
                }
            });
        });
    }

    private ChatResponse chatViaStreaming(StreamingChatModel model,
            List<ChatMessage> messages, List<ToolSpecification> tools) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);
        if (tools != null && !tools.isEmpty()) {
            log.trace("Calling Responses API with {} tools", tools.size());
            requestBuilder.toolSpecifications(tools);
        }
        model.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse chatResponse) {
                future.complete(chatResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future.join();
    }

    /**
     * Stream a chat request via the Responses API, emitting incremental
     * {@link LlmChunk} objects.
     */
    private Flux<LlmChunk> streamViaResponsesApi(StreamingChatModel model,
            List<ChatMessage> messages, List<ToolSpecification> tools) {
        return Flux.create(sink -> {
            ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);
            if (tools != null && !tools.isEmpty()) {
                requestBuilder.toolSpecifications(tools);
            }
            model.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (!sink.isCancelled()) {
                        sink.next(LlmChunk.builder()
                                .text(partialResponse)
                                .done(false)
                                .build());
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    if (!sink.isCancelled()) {
                        LlmResponse llmResponse = convertResponse(chatResponse, false, false);
                        sink.next(LlmChunk.builder()
                                .text(llmResponse.getContent())
                                .done(true)
                                .usage(llmResponse.getUsage())
                                .finishReason(llmResponse.getFinishReason())
                                .build());
                    }
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    sink.error(error);
                }
            });
        });
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<String> getSupportedModels() {
        // Build from models.json - provider/modelName for each configured provider.
        List<String> models = new ArrayList<>();
        Map<String, ModelCatalogEntry> modelsConfig = modelConfig
                .getAllModels();

        if (modelsConfig != null) {
            for (Map.Entry<String, ModelCatalogEntry> entry : modelsConfig
                    .entrySet()) {
                String modelName = entry.getKey();
                String provider = entry.getValue().getProvider();
                if (runtimeConfigService.hasLlmProviderApiKey(provider)) {
                    models.add(provider + "/" + modelName);
                }
            }
        }
        return models;
    }

    @Override
    public String getCurrentModel() {
        return currentModel;
    }

    @Override
    public boolean isAvailable() {
        return runtimeConfigService.getConfiguredLlmProviders().stream()
                .anyMatch(runtimeConfigService::hasLlmProviderApiKey);
    }

    @Override
    public LlmPort getLlmPort() {
        return this;
    }

    private MessageConversionResult buildChatMessages(LlmRequest request) {
        return messageConverter.convertMessages(request.getSystemPrompt(), request.getMessages(),
                isGeminiRequest(request),
                isVisionCapableRequest(request), request.isDisableToolAttachmentHydration());
    }

    // Package-private for reflection-based adapter tests in the same package.
    List<ChatMessage> convertMessages(LlmRequest request) {
        return buildChatMessages(request).messages();
    }

    private List<ChatMessage> convertMessagesWithFlattenedToolHistory(LlmRequest request) {
        List<Message> flattenedMessages = Message.flattenToolMessages(request.getMessages());
        return messageConverter.convertMessages(request.getSystemPrompt(), flattenedMessages, isGeminiRequest(request),
                isVisionCapableRequest(request), request.isDisableToolAttachmentHydration()).messages();
    }

    private boolean isGeminiRequest(LlmRequest request) {
        String model = request != null && request.getModel() != null && !request.getModel().isBlank()
                ? request.getModel()
                : currentModel;
        if (model == null || model.isBlank()) {
            return false;
        }
        String provider = getProvider(model);
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return API_TYPE_GEMINI.equals(getApiType(getProviderConfig(provider)));
    }

    private boolean isVisionCapableRequest(LlmRequest request) {
        String model = request != null && request.getModel() != null && !request.getModel().isBlank()
                ? request.getModel()
                : currentModel;
        if (model == null || model.isBlank() || modelConfig == null) {
            return false;
        }
        return modelConfig.supportsVision(model);
    }

    private boolean isUnsupportedFunctionRoleError(Throwable throwable) {
        return Langchain4jMessageConverter.isUnsupportedFunctionRoleError(throwable);
    }

    private List<ToolSpecification> convertTools(LlmRequest request) {
        return toolSchemaConverter.convertTools(request);
    }

    private LlmResponse convertResponse(ChatResponse response, boolean compatibilityFlatteningApplied,
            boolean geminiApiType) {
        return responseMapper.convertResponse(response, currentModel, compatibilityFlatteningApplied, geminiApiType);
    }

    private LlmResponse withProviderMetadata(LlmResponse response, Map<String, Object> extraMetadata) {
        return Langchain4jResponseMapper.withProviderMetadata(response, extraMetadata);
    }

    String convertArgsToJson(Object args) {
        if (args instanceof Map<?, ?> rawMap) {
            Map<String, Object> casted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    casted.put(key, entry.getValue());
                }
            }
            return Langchain4jToolArgumentJson.toJson(casted, objectMapper);
        }
        return Langchain4jToolArgumentJson.toJson(null, objectMapper);
    }

    Map<String, Object> parseJsonArgs(Object json) {
        return Langchain4jToolArgumentJson.parse(json instanceof String stringValue ? stringValue : null, objectMapper);
    }

    protected void sleepBeforeRetry(long backoffMs) throws InterruptedException {
        Thread.sleep(backoffMs);
    }
}
