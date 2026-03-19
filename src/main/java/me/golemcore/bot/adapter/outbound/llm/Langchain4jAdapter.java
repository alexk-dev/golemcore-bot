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
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.service.ToolArtifactService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.LlmPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
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
@RequiredArgsConstructor
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
    private static final String GEMINI_THINKING_SIGNATURE_KEY = "thinking_signature";
    private static final String TOOL_ATTACHMENTS_METADATA_KEY = "toolAttachments";
    private static final String SYNTH_ID_PREFIX = "synth_call_";
    private static final String SCHEMA_KEY_PROPERTIES = "properties";
    private static final java.util.regex.Pattern RESET_SECONDS_PATTERN = java.util.regex.Pattern
            .compile("\"reset_seconds\"\\s*:\\s*(\\d+)");
    private static final String TOOL_ATTACHMENT_REOPEN_HINT = "Re-open the file with a tool if deeper inspection is needed.";

    private record MessageConversionResult(List<ChatMessage> messages, boolean hydratedToolImages) {
    }

    private final RuntimeConfigService runtimeConfigService;
    private final ModelConfigService modelConfig;
    private final ToolArtifactService toolArtifactService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatModel chatModel;
    private String currentModel;
    private volatile boolean initialized = false;

    @Override
    public synchronized void initialize() {
        if (initialized)
            return;

        // Use balanced model from router config
        String model = runtimeConfigService.getBalancedModel();
        String reasoning = runtimeConfigService.getBalancedModelReasoning();
        this.currentModel = model;

        try {
            this.chatModel = createModel(model, reasoning);
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

    private String stripProviderPrefix(String model) {
        return model.contains("/") ? model.substring(model.indexOf('/') + 1) : model;
    }

    /**
     * Create a model instance based on configuration.
     */
    private ChatModel createModel(String model, String reasoningEffort) {
        String provider = getProvider(model);
        RuntimeConfig.LlmProviderConfig config = getProviderConfig(provider);
        String modelName = stripProviderPrefix(model);
        String apiType = getApiType(config);

        switch (apiType) {
        case API_TYPE_ANTHROPIC:
            return createAnthropicModel(modelName, config);
        case API_TYPE_GEMINI:
            return createGeminiModel(modelName, config);
        default:
            return createOpenAiModel(modelName, model, reasoningEffort, config);
        }
    }

    private String getApiType(RuntimeConfig.LlmProviderConfig config) {
        String apiType = config.getApiType();
        if (apiType == null || apiType.isBlank()) {
            return API_TYPE_OPENAI;
        }
        return apiType.trim().toLowerCase(Locale.ROOT);
    }

    private ChatModel createAnthropicModel(String modelName, RuntimeConfig.LlmProviderConfig config) {
        String apiKey = Secret.valueOrEmpty(config.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for provider anthropic in runtime config");
        }
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0) // Retry handled by our backoff logic
                .maxTokens(4096)
                .timeout(java.time.Duration.ofSeconds(
                        config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (supportsTemperature(modelName)) {
            builder.temperature(runtimeConfigService.getTemperature());
        }

        return builder.build();
    }

    private ChatModel createGeminiModel(String modelName, RuntimeConfig.LlmProviderConfig config) {
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
                .timeout(java.time.Duration.ofSeconds(
                        config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300));

        if (supportsTemperature(modelName)) {
            builder.temperature(runtimeConfigService.getTemperature());
        }

        return builder.build();
    }

    private ChatModel createOpenAiModel(String modelName, String fullModel,
            String reasoningEffort, RuntimeConfig.LlmProviderConfig config) {
        String apiKey = Secret.valueOrEmpty(config.getApiKey());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing apiKey for provider in runtime config");
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxRetries(0) // Retry handled by our backoff logic
                .timeout(java.time.Duration.ofSeconds(
                        config.getRequestTimeoutSeconds() != null ? config.getRequestTimeoutSeconds() : 300));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (supportsTemperature(fullModel)) {
            builder.temperature(runtimeConfigService.getTemperature());
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
            if (chatModel == null) {
                throw new RuntimeException("Langchain4j adapter not available");
            }

            // Handle per-request model/reasoning override
            ChatModel modelToUse = getModelForRequest(request);
            boolean geminiApiType = isGeminiRequest(request);
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
                    if (tools != null && !tools.isEmpty()) {
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
                        log.warn("[LLM] Rate limit hit (attempt {}/{}), retrying in {}ms{}...",
                                attempt + 1, MAX_RETRIES, backoffMs,
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
        // Walk the cause chain looking for rate limit indicators
        Throwable current = e;
        while (current != null) {
            // langchain4j maps HTTP 429 to RateLimitException regardless of body content
            if (current instanceof dev.langchain4j.exception.RateLimitException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null && (msg.contains("rate_limit") || msg.contains("token_quota_exceeded")
                    || msg.contains("too_many_tokens") || msg.contains("Too Many Requests")
                    || msg.contains("429") || msg.contains("model_cooldown")
                    || msg.contains("cooling down"))) {
                return true;
            }
            current = current.getCause();
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
                .reasoningEffort(request.getReasoningEffort())
                .build();
    }

    private ChatModel getModelForRequest(LlmRequest request) {
        String requestModel = request.getModel();
        String reasoningEffort = request.getReasoningEffort();

        // If request specifies a different model, create one-off
        if (requestModel != null && !requestModel.equals(currentModel)) {
            log.trace("Creating one-off model for request: {}, reasoning: {}", requestModel, reasoningEffort);
            return createModel(requestModel, reasoningEffort);
        }

        // If request specifies different reasoning for current model
        if (reasoningEffort != null && isReasoningRequired(currentModel)) {
            log.debug("Using reasoning effort: {} for model: {}", reasoningEffort, currentModel);
            return createModel(currentModel, reasoningEffort);
        }

        return chatModel;
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        ensureInitialized();
        // Basic implementation - langchain4j streaming support varies by model
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

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<String> getSupportedModels() {
        // Build from models.json — provider/modelName for each configured provider
        List<String> models = new ArrayList<>();
        Map<String, ModelConfigService.ModelSettings> modelsConfig = modelConfig
                .getAllModels();

        if (modelsConfig != null) {
            for (Map.Entry<String, ModelConfigService.ModelSettings> entry : modelsConfig
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

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
    };

    private MessageConversionResult buildChatMessages(LlmRequest request) {
        return convertMessages(request, isGeminiRequest(request));
    }

    // Package-private for reflection-based adapter tests in the same package.
    List<ChatMessage> convertMessages(LlmRequest request) {
        return buildChatMessages(request).messages();
    }

    private MessageConversionResult convertMessages(LlmRequest request, boolean geminiApiType) {
        return convertMessages(request.getSystemPrompt(), request.getMessages(), geminiApiType,
                isVisionCapableRequest(request), request.isDisableToolAttachmentHydration());
    }

    private List<ChatMessage> convertMessagesWithFlattenedToolHistory(LlmRequest request) {
        return convertMessagesWithFlattenedToolHistory(request, isGeminiRequest(request));
    }

    private List<ChatMessage> convertMessagesWithFlattenedToolHistory(LlmRequest request, boolean geminiApiType) {
        List<Message> flattenedMessages = Message.flattenToolMessages(request.getMessages());
        return convertMessages(request.getSystemPrompt(), flattenedMessages, geminiApiType,
                isVisionCapableRequest(request), request.isDisableToolAttachmentHydration()).messages();
    }

    private MessageConversionResult convertMessages(String systemPrompt, List<Message> requestMessages,
            boolean geminiApiType, boolean visionCapableTarget, boolean disableToolAttachmentHydration) {
        List<ChatMessage> messages = new ArrayList<>();
        List<Message> normalizedMessages = normalizeMessagesForProvider(requestMessages, geminiApiType);
        boolean hydratedToolImages = false;

        // Add system message
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }

        if (normalizedMessages == null) {
            return new MessageConversionResult(messages, false);
        }

        // Synthetic ID generation: langchain4j serializes null-ID tool calls as
        // legacy "function_call" and null-ID tool results as "role: function",
        // both rejected by GPT-5.2+. Assign synthetic IDs where missing.
        // Also track emitted tool call IDs to detect orphaned tool messages
        // whose preceding assistant message was removed (e.g. by compaction).
        int synthCounter = 0;
        Deque<String> pendingSynthIds = new ArrayDeque<>();
        Set<String> emittedToolCallIds = new HashSet<>();
        int firstTrailingToolIndex = findFirstTrailingToolMessageIndex(normalizedMessages);

        for (int index = 0; index < normalizedMessages.size(); index++) {
            Message msg = normalizedMessages.get(index);
            switch (msg.getRole()) {
            case "user" -> messages.add(toUserMessage(msg));
            case "assistant" -> {
                if (msg.hasToolCalls()) {
                    List<ToolExecutionRequest> toolRequests = new ArrayList<>();
                    for (Message.ToolCall tc : msg.getToolCalls()) {
                        String id = tc.getId();
                        if (id == null || id.isBlank()) {
                            synthCounter++;
                            id = SYNTH_ID_PREFIX + synthCounter;
                            pendingSynthIds.addLast(id);
                        }
                        emittedToolCallIds.add(id);
                        toolRequests.add(ToolExecutionRequest.builder()
                                .id(id)
                                .name(tc.getName())
                                .arguments(convertArgsToJson(tc.getArguments()))
                                .build());
                    }
                    AiMessage.Builder aiMessageBuilder = AiMessage.builder()
                            .toolExecutionRequests(toolRequests);
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        aiMessageBuilder.text(msg.getContent());
                    }
                    String thinkingSignature = geminiApiType ? extractGeminiThinkingSignature(msg) : null;
                    if (thinkingSignature != null) {
                        aiMessageBuilder.attributes(Map.of(GEMINI_THINKING_SIGNATURE_KEY, thinkingSignature));
                    }
                    messages.add(aiMessageBuilder.build());
                } else {
                    messages.add(AiMessage.from(nonNullText(msg.getContent())));
                }
            }
            case "tool" -> {
                String toolCallId = msg.getToolCallId();
                if (toolCallId == null || toolCallId.isBlank()) {
                    if (pendingSynthIds.isEmpty()) {
                        synthCounter++;
                        toolCallId = SYNTH_ID_PREFIX + synthCounter;
                    } else {
                        toolCallId = pendingSynthIds.pollFirst();
                    }
                }
                if (emittedToolCallIds.contains(toolCallId)) {
                    messages.add(ToolExecutionResultMessage.from(
                            toolCallId,
                            msg.getToolName(),
                            nonNullText(msg.getContent())));
                    boolean hydrateToolImages = shouldHydrateToolAttachments(index, firstTrailingToolIndex,
                            visionCapableTarget, disableToolAttachmentHydration);
                    UserMessage toolVisualContext = toToolAttachmentContextMessage(msg, hydrateToolImages);
                    if (toolVisualContext != null) {
                        messages.add(toolVisualContext);
                        hydratedToolImages = hydratedToolImages || hasImageContent(toolVisualContext);
                    }
                } else {
                    log.debug("[LLM] Converting orphaned tool message to text: tool={}",
                            msg.getToolName());
                    String toolText = "[Tool: " + nonNullText(msg.getToolName())
                            + "]\n[Result: " + nonNullText(msg.getContent()) + "]";
                    boolean hydrateToolImages = shouldHydrateToolAttachments(index, firstTrailingToolIndex,
                            visionCapableTarget, disableToolAttachmentHydration);
                    UserMessage orphanedToolContext = toToolAttachmentContextMessage(msg, hydrateToolImages);
                    messages.add(orphanedToolContext != null ? orphanedToolContext : UserMessage.from(toolText));
                    if (orphanedToolContext != null) {
                        hydratedToolImages = hydratedToolImages || hasImageContent(orphanedToolContext);
                    }
                }
            }
            case "system" -> messages.add(SystemMessage.from(nonNullText(msg.getContent())));
            default -> log.warn("Unknown message role: {}, treating as user message", msg.getRole());
            }
        }

        return new MessageConversionResult(messages, hydratedToolImages);
    }

    private List<Message> normalizeMessagesForProvider(List<Message> requestMessages, boolean geminiApiType) {
        if (!geminiApiType || requestMessages == null || requestMessages.isEmpty()) {
            return requestMessages;
        }

        long missingSignatureCount = requestMessages.stream()
                .filter(Message::isAssistantMessage)
                .filter(Message::hasToolCalls)
                .filter(msg -> extractGeminiThinkingSignature(msg) == null)
                .count();
        if (missingSignatureCount == 0) {
            return requestMessages;
        }

        log.warn(
                "[LLM] Flattening tool history for Gemini request because {} assistant tool-call message(s) are missing thinking_signature",
                missingSignatureCount);
        return Message.flattenToolMessages(requestMessages);
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

    private String extractGeminiThinkingSignature(Message msg) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }
        Object value = msg.getMetadata().get(GEMINI_THINKING_SIGNATURE_KEY);
        if (value instanceof String signature && !signature.isBlank()) {
            return signature;
        }
        return null;
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

    private String nonNullText(String text) {
        return text != null ? text : "";
    }

    private int findFirstTrailingToolMessageIndex(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }

        int index = messages.size() - 1;
        while (index >= 0) {
            Message message = messages.get(index);
            if (message == null || !"tool".equals(message.getRole())) {
                break;
            }
            index--;
        }

        int firstTrailingToolIndex = index + 1;
        return firstTrailingToolIndex < messages.size() ? firstTrailingToolIndex : -1;
    }

    private boolean shouldHydrateToolAttachments(int messageIndex, int firstTrailingToolIndex,
            boolean visionCapableTarget, boolean disableToolAttachmentHydration) {
        return visionCapableTarget
                && !disableToolAttachmentHydration
                && firstTrailingToolIndex >= 0
                && messageIndex >= firstTrailingToolIndex;
    }

    private boolean isUnsupportedFunctionRoleError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                // Legacy function role rejected by newer models
                if (message.contains("unsupported_value")
                        && message.contains("does not support 'function'")) {
                    return true;
                }
                // Orphaned tool message without preceding tool_calls
                if (message.contains("role 'tool' must be a response to")
                        && message.contains("tool_calls")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private UserMessage toUserMessage(Message msg) {
        List<Content> contents = new ArrayList<>();

        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            contents.add(TextContent.from(msg.getContent()));
        }

        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null) {
            Object attachmentsRaw = metadata.get("attachments");
            if (attachmentsRaw instanceof List<?> attachments) {
                for (Object attachmentObj : attachments) {
                    if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                        continue;
                    }

                    Object typeObj = attachmentMap.get("type");
                    Object mimeObj = attachmentMap.get("mimeType");
                    Object dataObj = attachmentMap.get("dataBase64");

                    if (!(typeObj instanceof String type)
                            || !(mimeObj instanceof String mimeType)
                            || !(dataObj instanceof String base64Data)) {
                        continue;
                    }
                    if (!"image".equals(type) || !mimeType.startsWith("image/") || base64Data.isBlank()) {
                        continue;
                    }

                    Image image = Image.builder()
                            .base64Data(base64Data)
                            .mimeType(mimeType)
                            .build();
                    contents.add(ImageContent.from(image));
                }
            }
        }

        if (contents.isEmpty()) {
            return UserMessage.from(msg.getContent() != null ? msg.getContent() : "");
        }

        return UserMessage.from(contents);
    }

    @SuppressWarnings("unchecked")
    private UserMessage toToolAttachmentContextMessage(Message msg, boolean hydrateImages) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }

        Object attachmentsRaw = msg.getMetadata().get(TOOL_ATTACHMENTS_METADATA_KEY);
        if (!(attachmentsRaw instanceof List<?> attachments)) {
            return null;
        }

        List<Content> contents = new ArrayList<>();
        String text = buildToolAttachmentContextText(msg, attachments);
        if (text != null && !text.isBlank()) {
            contents.add(TextContent.from(text));
        }

        if (!hydrateImages || toolArtifactService == null) {
            return contents.isEmpty() ? null : UserMessage.from(contents);
        }

        for (Object attachmentObj : attachments) {
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }

            Object typeObj = attachmentMap.get("type");
            Object pathObj = attachmentMap.get("internalFilePath");
            if (!(typeObj instanceof String type)
                    || !(pathObj instanceof String internalFilePath)
                    || !"image".equals(type)
                    || internalFilePath.isBlank()) {
                continue;
            }

            try {
                var download = toolArtifactService.getDownload(internalFilePath);
                String mimeType = download.getMimeType();
                if (mimeType == null || !mimeType.startsWith("image/")) {
                    continue;
                }
                String base64Data = Base64.getEncoder().encodeToString(download.getData());
                Image image = Image.builder()
                        .base64Data(base64Data)
                        .mimeType(mimeType)
                        .build();
                contents.add(ImageContent.from(image));
            } catch (RuntimeException ex) {
                log.warn("[LLM] Failed to hydrate tool image attachment '{}': {}", internalFilePath, ex.getMessage());
            }
        }

        if (contents.isEmpty()) {
            return null;
        }

        return UserMessage.from(contents);
    }

    private String buildToolAttachmentContextText(Message msg, List<?> attachments) {
        String toolName = msg.getToolName() != null && !msg.getToolName().isBlank()
                ? msg.getToolName()
                : "tool";
        List<String> lines = new ArrayList<>();
        lines.add("Tool artifact from " + toolName + " is available.");

        boolean foundAttachment = false;
        for (Object attachmentObj : attachments) {
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }
            String path = objectAsString(attachmentMap.get("internalFilePath"));
            if (path == null || path.isBlank()) {
                continue;
            }
            String name = objectAsString(attachmentMap.get("name"));
            String mimeType = objectAsString(attachmentMap.get("mimeType"));
            String displayName = name != null && !name.isBlank() ? name : path.substring(path.lastIndexOf('/') + 1);
            StringBuilder line = new StringBuilder("- ").append(displayName);
            if (mimeType != null && !mimeType.isBlank()) {
                line.append(" (").append(mimeType).append(")");
            }
            line.append(" @ ").append(path);
            lines.add(line.toString());
            foundAttachment = true;
        }

        if (!foundAttachment) {
            return null;
        }

        lines.add(TOOL_ATTACHMENT_REOPEN_HINT);
        return String.join("\n", lines);
    }

    private String objectAsString(Object value) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }

    private List<ToolSpecification> convertTools(LlmRequest request) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, ToolDefinition> uniqueTools = new LinkedHashMap<>();
        for (ToolDefinition tool : request.getTools()) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                log.warn("[LLM] Dropping tool with blank name before request serialization");
                continue;
            }
            ToolDefinition previous = uniqueTools.putIfAbsent(tool.getName(), tool);
            if (previous != null) {
                log.warn("[LLM] Dropping duplicate tool definition '{}' before request serialization", tool.getName());
            }
        }

        List<ToolSpecification> tools = new ArrayList<>(uniqueTools.size());
        for (ToolDefinition tool : uniqueTools.values()) {
            tools.add(convertToolDefinition(tool));
        }
        return tools;
    }

    private ToolSpecification convertToolDefinition(ToolDefinition tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription());

        // Convert input schema to JsonObjectSchema parameters
        if (tool.getInputSchema() != null) {
            Map<String, Object> schema = tool.getInputSchema();
            Map<String, Object> properties = stringObjectMap(schema.get(SCHEMA_KEY_PROPERTIES),
                    tool.getName(), SCHEMA_KEY_PROPERTIES);
            List<String> required = stringList(schema.get("required"), tool.getName(), "required");
            if (properties != null) {
                JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> paramSchema = stringObjectMap(entry.getValue(), tool.getName(),
                            SCHEMA_KEY_PROPERTIES + "." + paramName);
                    if (paramSchema == null) {
                        log.warn("[LLM] Dropping invalid schema for tool '{}' param '{}'", tool.getName(), paramName);
                        continue;
                    }
                    schemaBuilder.addProperty(paramName, toJsonSchemaElement(tool.getName(),
                            SCHEMA_KEY_PROPERTIES + "." + paramName, paramSchema));
                }
                if (required != null && !required.isEmpty()) {
                    schemaBuilder.required(required);
                }
                builder.parameters(schemaBuilder.build());
            }
        }

        return builder.build();
    }

    private JsonSchemaElement toJsonSchemaElement(String toolName, String path, Map<String, Object> paramSchema) {
        String type = stringValue(paramSchema.get("type"), toolName, path + ".type");
        String description = stringValue(paramSchema.get("description"), toolName, path + ".description");
        List<String> enumValues = stringList(paramSchema.get("enum"), toolName, path + ".enum");

        // Enum values take priority
        if (enumValues != null && !enumValues.isEmpty()) {
            JsonEnumSchema.Builder builder = JsonEnumSchema.builder().enumValues(enumValues);
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }

        if (type == null) {
            type = "string";
        }

        switch (type) {
        case "string" -> {
            JsonStringSchema.Builder builder = JsonStringSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "integer" -> {
            JsonIntegerSchema.Builder builder = JsonIntegerSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "number" -> {
            JsonNumberSchema.Builder builder = JsonNumberSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "boolean" -> {
            JsonBooleanSchema.Builder builder = JsonBooleanSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        case "array" -> {
            JsonArraySchema.Builder builder = JsonArraySchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            if (paramSchema.containsKey("items")) {
                Map<String, Object> items = stringObjectMap(paramSchema.get("items"), toolName, path + ".items");
                if (items != null) {
                    builder.items(toJsonSchemaElement(toolName, path + ".items", items));
                }
            }
            return builder.build();
        }
        case "object" -> {
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            if (paramSchema.containsKey(SCHEMA_KEY_PROPERTIES)) {
                Map<String, Object> nestedProps = stringObjectMap(paramSchema.get(SCHEMA_KEY_PROPERTIES),
                        toolName, path + "." + SCHEMA_KEY_PROPERTIES);
                if (nestedProps != null) {
                    for (Map.Entry<String, Object> entry : nestedProps.entrySet()) {
                        Map<String, Object> nestedSchema = stringObjectMap(entry.getValue(), toolName,
                                path + "." + SCHEMA_KEY_PROPERTIES + "." + entry.getKey());
                        if (nestedSchema == null) {
                            log.warn("[LLM] Dropping invalid nested schema for tool '{}' at {}", toolName,
                                    path + "." + SCHEMA_KEY_PROPERTIES + "." + entry.getKey());
                            continue;
                        }
                        builder.addProperty(entry.getKey(), toJsonSchemaElement(toolName,
                                path + "." + SCHEMA_KEY_PROPERTIES + "." + entry.getKey(), nestedSchema));
                    }
                }
            }
            return builder.build();
        }
        default -> {
            // Fallback to string for unknown types
            JsonStringSchema.Builder builder = JsonStringSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            return builder.build();
        }
        }
    }

    @SuppressWarnings({
            "unchecked",
            "PMD.ReturnEmptyCollectionRatherThanNull"
    })
    private Map<String, Object> stringObjectMap(Object rawValue, String toolName, String path) {
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            if (rawValue != null) {
                log.warn("[LLM] Invalid schema object for tool '{}' at {}: {}", toolName, path,
                        rawValue.getClass().getSimpleName());
            }
            return null;
        }
        Map<String, Object> casted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                log.warn("[LLM] Dropping non-string schema key for tool '{}' at {}", toolName, path);
                continue;
            }
            casted.put(key, (Object) entry.getValue());
        }
        return casted;
    }

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private List<String> stringList(Object rawValue, String toolName, String path) {
        if (!(rawValue instanceof List<?> rawList)) {
            if (rawValue != null) {
                log.warn("[LLM] Invalid schema list for tool '{}' at {}: {}", toolName, path,
                        rawValue.getClass().getSimpleName());
            }
            return null;
        }
        List<String> values = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item instanceof String stringValue && !stringValue.isBlank()) {
                values.add(stringValue);
            } else {
                log.warn("[LLM] Dropping non-string schema list item for tool '{}' at {}", toolName, path);
            }
        }
        return values;
    }

    private String stringValue(Object rawValue, String toolName, String path) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof String stringValue) {
            return stringValue;
        }
        log.warn("[LLM] Invalid schema string for tool '{}' at {}: {}", toolName, path,
                rawValue.getClass().getSimpleName());
        return null;
    }

    private LlmResponse convertResponse(ChatResponse response, boolean compatibilityFlatteningApplied,
            boolean geminiApiType) {
        AiMessage aiMessage = response.aiMessage();

        // Convert tool calls if present
        List<Message.ToolCall> toolCalls = null;
        if (aiMessage.hasToolExecutionRequests()) {
            toolCalls = aiMessage.toolExecutionRequests().stream()
                    .map(ter -> Message.ToolCall.builder()
                            .id(ter.id())
                            .name(ter.name())
                            .arguments(parseJsonArgs(ter.arguments()))
                            .build())
                    .toList();
            log.trace("Parsed {} tool calls from response", toolCalls.size());
        }

        LlmUsage usage = null;
        if (response.tokenUsage() != null) {
            usage = LlmUsage.builder()
                    .inputTokens(response.tokenUsage().inputTokenCount())
                    .outputTokens(response.tokenUsage().outputTokenCount())
                    .totalTokens(response.tokenUsage().totalTokenCount())
                    .build();
        }

        Map<String, Object> providerMetadata = extractProviderMetadata(aiMessage, geminiApiType);

        return LlmResponse.builder()
                .content(aiMessage.text())
                .toolCalls(toolCalls)
                .usage(usage)
                .model(currentModel)
                .finishReason(response.finishReason() != null ? response.finishReason().name() : "stop")
                .providerMetadata(providerMetadata.isEmpty() ? null : providerMetadata)
                .compatibilityFlatteningApplied(compatibilityFlatteningApplied)
                .build();
    }

    private LlmResponse withProviderMetadata(LlmResponse response, Map<String, Object> extraMetadata) {
        if (response == null || extraMetadata == null || extraMetadata.isEmpty()) {
            return response;
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        if (response.getProviderMetadata() != null) {
            merged.putAll(response.getProviderMetadata());
        }
        merged.putAll(extraMetadata);

        return LlmResponse.builder()
                .content(response.getContent())
                .toolCalls(response.getToolCalls())
                .usage(response.getUsage())
                .model(response.getModel())
                .finishReason(response.getFinishReason())
                .providerMetadata(merged)
                .compatibilityFlatteningApplied(response.isCompatibilityFlatteningApplied())
                .build();
    }

    private Map<String, Object> extractProviderMetadata(AiMessage aiMessage, boolean geminiApiType) {
        if (!geminiApiType || aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
            return Collections.emptyMap();
        }
        String thinkingSignature = aiMessage.attribute(GEMINI_THINKING_SIGNATURE_KEY, String.class);
        if (thinkingSignature == null || thinkingSignature.isBlank()) {
            return Collections.emptyMap();
        }
        return Map.of(GEMINI_THINKING_SIGNATURE_KEY, thinkingSignature);
    }

    private boolean hasImageContent(UserMessage message) {
        if (message == null || message.contents() == null) {
            return false;
        }
        for (Content content : message.contents()) {
            if (content.type() == dev.langchain4j.data.message.ContentType.IMAGE) {
                return true;
            }
        }
        return false;
    }

    private String convertArgsToJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("Failed to serialize tool arguments: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> parseJsonArgs(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE_REF);
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    protected void sleepBeforeRetry(long backoffMs) throws InterruptedException {
        Thread.sleep(backoffMs);
    }
}
