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
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.LlmPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
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
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String SCHEMA_KEY_PROPERTIES = "properties";
    private static final java.util.regex.Pattern RESET_SECONDS_PATTERN = java.util.regex.Pattern
            .compile("\"reset_seconds\"\\s*:\\s*(\\d+)");

    private final BotProperties properties;
    private final ModelConfigService modelConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatModel chatModel;
    private String currentModel;
    private volatile boolean initialized = false;

    @Override
    public synchronized void initialize() {
        if (initialized)
            return;

        // Use balanced model from router config
        String model = properties.getRouter().getBalancedModel();
        String reasoning = properties.getRouter().getBalancedModelReasoning();
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

    private BotProperties.ProviderProperties getProviderConfig(String providerName) {
        var config = properties.getLlm().getLangchain4j().getProviders().get(providerName);
        if (config == null) {
            throw new IllegalStateException("Provider not configured: " + providerName
                    + ". Add bot.llm.langchain4j.providers." + providerName + ".api-key");
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
        BotProperties.ProviderProperties config = getProviderConfig(provider);
        String modelName = stripProviderPrefix(model);

        if (PROVIDER_ANTHROPIC.equals(provider)) {
            return createAnthropicModel(modelName, config);
        } else {
            // All non-Anthropic providers use OpenAI-compatible API
            return createOpenAiModel(modelName, model, reasoningEffort, config);
        }
    }

    private ChatModel createAnthropicModel(String modelName, BotProperties.ProviderProperties config) {
        var builder = AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(modelName)
                .maxRetries(0) // Retry handled by our backoff logic
                .maxTokens(4096)
                .timeout(Duration.ofMillis(properties.getLlm().getLangchain4j().getTimeoutMs()));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (supportsTemperature(modelName)) {
            builder.temperature(properties.getRouter().getTemperature());
        }

        return builder.build();
    }

    private ChatModel createOpenAiModel(String modelName, String fullModel,
            String reasoningEffort, BotProperties.ProviderProperties config) {
        var builder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(modelName)
                .maxRetries(0) // Retry handled by our backoff logic
                .timeout(Duration.ofMillis(properties.getLlm().getLangchain4j().getTimeoutMs()));

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        if (supportsTemperature(fullModel)) {
            builder.temperature(properties.getRouter().getTemperature());
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
            List<ChatMessage> messages = convertMessages(request);
            List<ToolSpecification> tools = convertTools(request);

            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
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

                    return convertResponse(response);
                } catch (Exception e) {
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
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("LLM chat interrupted during retry backoff", ie);
                        }
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
        var providers = properties.getLlm().getLangchain4j().getProviders();
        var modelsConfig = modelConfig.getAllModels();

        if (modelsConfig != null) {
            for (var entry : modelsConfig.entrySet()) {
                String modelName = entry.getKey();
                String provider = entry.getValue().getProvider();
                if (providers.containsKey(provider)) {
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
        return properties.getLlm().getLangchain4j().getProviders().values().stream()
                .anyMatch(p -> p.getApiKey() != null && !p.getApiKey().isBlank());
    }

    @Override
    public LlmPort getLlmPort() {
        return this;
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
    };

    private List<ChatMessage> convertMessages(LlmRequest request) {
        List<ChatMessage> messages = new ArrayList<>();

        // Add system message
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(SystemMessage.from(request.getSystemPrompt()));
        }

        // Convert conversation messages — tool call IDs and names are passed through
        // as-is. Model switches are handled upstream by LlmExecutionSystem which
        // flattens tool messages to plain text before they reach the adapter.
        for (Message msg : request.getMessages()) {
            switch (msg.getRole()) {
            case "user" -> messages.add(UserMessage.from(msg.getContent()));
            case "assistant" -> {
                if (msg.hasToolCalls()) {
                    List<ToolExecutionRequest> toolRequests = msg.getToolCalls().stream()
                            .map(tc -> ToolExecutionRequest.builder()
                                    .id(tc.getId())
                                    .name(tc.getName())
                                    .arguments(convertArgsToJson(tc.getArguments()))
                                    .build())
                            .toList();
                    messages.add(AiMessage.from(toolRequests));
                } else {
                    messages.add(AiMessage.from(msg.getContent()));
                }
            }
            case "tool" -> {
                messages.add(ToolExecutionResultMessage.from(
                        msg.getToolCallId(),
                        msg.getToolName(),
                        msg.getContent()));
            }
            case "system" -> messages.add(SystemMessage.from(msg.getContent()));
            default -> log.warn("Unknown message role: {}, treating as user message", msg.getRole());
            }
        }

        return messages;
    }

    private List<ToolSpecification> convertTools(LlmRequest request) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return Collections.emptyList();
        }

        return request.getTools().stream()
                .map(this::convertToolDefinition)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private ToolSpecification convertToolDefinition(ToolDefinition tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription());

        // Convert input schema to JsonObjectSchema parameters
        if (tool.getInputSchema() != null) {
            Map<String, Object> schema = tool.getInputSchema();
            Map<String, Object> properties = (Map<String, Object>) schema.get(SCHEMA_KEY_PROPERTIES);
            List<String> required = (List<String>) schema.get("required");

            if (properties != null) {
                JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();
                    schemaBuilder.addProperty(paramName, toJsonSchemaElement(paramSchema));
                }
                if (required != null && !required.isEmpty()) {
                    schemaBuilder.required(required);
                }
                builder.parameters(schemaBuilder.build());
            }
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private JsonSchemaElement toJsonSchemaElement(Map<String, Object> paramSchema) {
        String type = (String) paramSchema.get("type");
        String description = (String) paramSchema.get("description");
        List<String> enumValues = (List<String>) paramSchema.get("enum");

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
                Map<String, Object> items = (Map<String, Object>) paramSchema.get("items");
                builder.items(toJsonSchemaElement(items));
            }
            return builder.build();
        }
        case "object" -> {
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            if (description != null && !description.isBlank()) {
                builder.description(description);
            }
            if (paramSchema.containsKey(SCHEMA_KEY_PROPERTIES)) {
                Map<String, Object> nestedProps = (Map<String, Object>) paramSchema.get(SCHEMA_KEY_PROPERTIES);
                for (Map.Entry<String, Object> entry : nestedProps.entrySet()) {
                    builder.addProperty(entry.getKey(), toJsonSchemaElement((Map<String, Object>) entry.getValue()));
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

    private LlmResponse convertResponse(ChatResponse response) {
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

        return LlmResponse.builder()
                .content(aiMessage.text())
                .toolCalls(toolCalls)
                .usage(usage)
                .model(currentModel)
                .finishReason(response.finishReason() != null ? response.finishReason().name() : "stop")
                .build();
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
}
