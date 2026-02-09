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
import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.infrastructure.http.FeignClientFactory;
import me.golemcore.bot.port.outbound.LlmPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Custom LLM adapter for OpenAI-compatible APIs using Feign + OkHttp.
 *
 * <p>
 * This adapter allows connecting to any OpenAI-compatible REST API (e.g., local
 * inference servers, third-party proxies). It uses Feign for declarative HTTP
 * clients and OkHttp for connection management.
 *
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code bot.llm.custom.api-url} - Base URL of the API
 * <li>{@code bot.llm.custom.api-key} - API key for authentication
 * </ul>
 *
 * <p>
 * Provider ID: {@code "custom"}
 *
 * <p>
 * Lazy initialization: The adapter only connects to the API on first use.
 *
 * @see LlmProviderAdapter
 * @see me.golemcore.bot.infrastructure.http.FeignClientFactory
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomLlmAdapter implements LlmProviderAdapter, LlmComponent {

    private final BotProperties properties;
    private final FeignClientFactory feignClientFactory;
    private final ModelConfigService modelConfig;

    private CustomLlmApi client;
    private String apiKey;
    private String currentModel;
    private volatile boolean initialized = false;

    @Override
    public synchronized void initialize() {
        if (initialized)
            return;

        try {
            BotProperties.CustomLlmProperties customProps = properties.getLlm().getCustom();
            this.apiKey = customProps.getApiKey();
            this.currentModel = properties.getRouter().getDefaultModel();

            if (customProps.getApiUrl() != null && !customProps.getApiUrl().isBlank()) {
                this.client = feignClientFactory.create(CustomLlmApi.class, customProps.getApiUrl());
                initialized = true;
                log.info("Custom LLM adapter initialized with URL: {}", customProps.getApiUrl());
            }
        } catch (Exception e) {
            log.warn("Failed to initialize Custom LLM adapter: {}", e.getMessage());
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    @Override
    public String getProviderId() {
        return "custom";
    }

    @Override
    public CompletableFuture<LlmResponse> chat(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            if (client == null) {
                throw new RuntimeException("Custom LLM adapter not available");
            }
            try {
                ChatCompletionRequest apiRequest = buildRequest(request);
                ChatCompletionResponse apiResponse = client.chatCompletion(apiKey, apiRequest);
                return convertResponse(apiResponse);
            } catch (Exception e) {
                log.error("Custom LLM chat failed", e);
                throw new RuntimeException("Custom LLM chat failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public Flux<LlmChunk> chatStream(LlmRequest request) {
        ensureInitialized();
        // Simplified streaming - in production, use SSE
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
        return false;
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of(currentModel != null ? currentModel : "custom");
    }

    @Override
    public String getCurrentModel() {
        return currentModel;
    }

    @Override
    public boolean isAvailable() {
        BotProperties.CustomLlmProperties customProps = properties.getLlm().getCustom();
        return customProps.getApiUrl() != null && !customProps.getApiUrl().isBlank() &&
                customProps.getApiKey() != null && !customProps.getApiKey().isBlank();
    }

    @Override
    public LlmPort getLlmPort() {
        return this;
    }

    private boolean supportsTemperature(String model) {
        return modelConfig.supportsTemperature(model);
    }

    private ChatCompletionRequest buildRequest(LlmRequest request) {
        ChatCompletionRequest apiRequest = new ChatCompletionRequest();
        apiRequest.setModel(currentModel);

        // Apply temperature only if model supports it
        if (supportsTemperature(currentModel)) {
            apiRequest.setTemperature(request.getTemperature());
        }

        List<ApiMessage> messages = new ArrayList<>();

        // System message
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            ApiMessage sysMsg = new ApiMessage();
            sysMsg.setRole("system");
            sysMsg.setContent(request.getSystemPrompt());
            messages.add(sysMsg);
        }

        // Conversation messages
        for (Message msg : request.getMessages()) {
            ApiMessage apiMsg = new ApiMessage();
            apiMsg.setRole(msg.getRole());
            apiMsg.setContent(msg.getContent());

            if (msg.hasToolCalls()) {
                apiMsg.setToolCalls(msg.getToolCalls().stream()
                        .map(tc -> {
                            ApiToolCall atc = new ApiToolCall();
                            atc.setId(tc.getId());
                            atc.setType("function");
                            ApiFunction func = new ApiFunction();
                            func.setName(tc.getName());
                            func.setArguments(convertArgsToJson(tc.getArguments()));
                            atc.setFunction(func);
                            return atc;
                        })
                        .toList());
            }

            if (msg.getToolCallId() != null) {
                apiMsg.setToolCallId(msg.getToolCallId());
            }

            messages.add(apiMsg);
        }

        apiRequest.setMessages(messages);

        // Tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            apiRequest.setTools(request.getTools().stream()
                    .map(tool -> {
                        ApiTool apiTool = new ApiTool();
                        apiTool.setType("function");
                        ApiToolFunction func = new ApiToolFunction();
                        func.setName(tool.getName());
                        func.setDescription(tool.getDescription());
                        func.setParameters(tool.getInputSchema());
                        apiTool.setFunction(func);
                        return apiTool;
                    })
                    .toList());
        }

        return apiRequest;
    }

    private LlmResponse convertResponse(ChatCompletionResponse apiResponse) {
        if (apiResponse.getChoices() == null || apiResponse.getChoices().isEmpty()) {
            return LlmResponse.builder()
                    .content("")
                    .finishReason("error")
                    .build();
        }

        ChatChoice choice = apiResponse.getChoices().get(0);
        ApiMessage message = choice.getMessage();

        List<Message.ToolCall> toolCalls = null;
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            toolCalls = message.getToolCalls().stream()
                    .map(tc -> Message.ToolCall.builder()
                            .id(tc.getId())
                            .name(tc.getFunction().getName())
                            .arguments(parseJsonArgs(tc.getFunction().getArguments()))
                            .build())
                    .toList();
        }

        LlmUsage usage = null;
        if (apiResponse.getUsage() != null) {
            ApiUsage apiUsage = apiResponse.getUsage();
            usage = LlmUsage.builder()
                    .inputTokens(apiUsage.getPromptTokens())
                    .outputTokens(apiUsage.getCompletionTokens())
                    .totalTokens(apiUsage.getTotalTokens())
                    .build();
        }

        return LlmResponse.builder()
                .content(message.getContent())
                .toolCalls(toolCalls)
                .usage(usage)
                .model(apiResponse.getModel())
                .finishReason(choice.getFinishReason())
                .build();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private String convertArgsToJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return JSON_MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonArgs(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JSON_MAPPER.readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    // Feign API interface
    public interface CustomLlmApi {
        @RequestLine("POST /chat/completions")
        @Headers({
                "Content-Type: application/json",
                "Authorization: Bearer {apiKey}"
        })
        ChatCompletionResponse chatCompletion(@Param("apiKey") String apiKey, ChatCompletionRequest request);
    }

    // API DTOs
    @Data
    public static class ChatCompletionRequest {
        private String model;
        private List<ApiMessage> messages;
        private List<ApiTool> tools;
        private double temperature;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
    }

    @Data
    public static class ChatCompletionResponse {
        private String id;
        private String model;
        private List<ChatChoice> choices;
        private ApiUsage usage;
    }

    @Data
    public static class ChatChoice {
        private int index;
        private ApiMessage message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    public static class ApiMessage {
        private String role;
        private String content;
        @JsonProperty("tool_calls")
        private List<ApiToolCall> toolCalls;
        @JsonProperty("tool_call_id")
        private String toolCallId;
    }

    @Data
    public static class ApiTool {
        private String type;
        private ApiToolFunction function;
    }

    @Data
    public static class ApiToolFunction {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }

    @Data
    public static class ApiToolCall {
        private String id;
        private String type;
        private ApiFunction function;
    }

    @Data
    public static class ApiFunction {
        private String name;
        private String arguments;
    }

    @Data
    public static class ApiUsage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
