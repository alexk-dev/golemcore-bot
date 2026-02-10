package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * System for executing LLM chat completion requests with model tier selection
 * (order=30). Selects model based on tier (balanced/smart/coding/deep) from
 * SkillRoutingSystem or DynamicTierSystem, constructs LlmRequest with system
 * prompt and conversation history, handles context overflow errors with
 * emergency truncation and retry, detects empty responses, and tracks usage via
 * {@link usage.LlmUsageTracker}. Sets llm.response or llm.error in context for
 * downstream systems.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmExecutionSystem implements AgentSystem {

    private final LlmPort llmPort;
    private final UsageTrackingPort usageTracker;
    private final BotProperties properties;
    private final ModelConfigService modelConfigService;
    private final Clock clock;

    private static final long TIMEOUT_SECONDS = 120;
    private static final int MAX_EMPTY_RETRIES = 1;
    private static final double CHARS_PER_TOKEN = 3.5;
    private static final String ATTR_LLM_ERROR = "llm.error";

    @Override
    public String getName() {
        return "LlmExecutionSystem";
    }

    @Override
    public int getOrder() {
        return 30; // After context building
    }

    @Override
    public AgentContext process(AgentContext context) {
        String providerId = llmPort.getProviderId();
        String model = llmPort.getCurrentModel();
        log.debug("[LLM] Starting request to {} ({})", providerId, model);

        LlmRequest request = buildRequest(context);
        log.trace("[LLM] System prompt length: {} chars",
                context.getSystemPrompt() != null ? context.getSystemPrompt().length() : 0);
        log.trace("[LLM] Messages count: {}, Tools count: {}",
                request.getMessages().size(),
                request.getTools() != null ? request.getTools().size() : 0);

        // Log last user message
        if (!request.getMessages().isEmpty()) {
            Message lastMsg = request.getMessages().get(request.getMessages().size() - 1);
            log.trace("[LLM] Last message: role={}, content='{}'",
                    lastMsg.getRole(), truncate(lastMsg.getContent(), 100));
        }

        Instant start = clock.instant();
        try {
            LlmResponse response = callLlmWithRetry(llmPort, request, providerId, model, context);

            Duration latency = Duration.between(start, clock.instant());

            // Store response in context
            context.setAttribute("llm.response", response);
            context.setAttribute("llm.latency", latency);

            // If there are tool calls, store them for ToolExecutionSystem
            if (response.hasToolCalls()) {
                context.setAttribute("llm.toolCalls", response.getToolCalls());
                log.info("[LLM] Tool calls requested: {}",
                        response.getToolCalls().stream().map(tc -> tc.getName()).toList());
            }

            log.info("[LLM] Response received in {}ms, content length: {}, tool_calls: {}",
                    latency.toMillis(),
                    response.getContent() != null ? response.getContent().length() : 0,
                    response.hasToolCalls() ? response.getToolCalls().size() : 0);
            log.trace("[LLM] Response content: '{}'", truncate(response.getContent(), 200));

            // Flag empty response as error so ResponseRoutingSystem can notify the user
            if (isEmptyResponse(response)) {
                log.warn("[LLM] Empty response after retries (output tokens: {})",
                        response.getUsage() != null ? response.getUsage().getOutputTokens() : 0);
                context.setAttribute(ATTR_LLM_ERROR, "LLM returned empty response");
            }

        } catch (Exception e) {
            if (isContextOverflowError(e)) {
                log.warn("[LLM] Context overflow detected, truncating large messages and retrying...");
                int truncated = truncateLargeMessages(context);
                if (truncated > 0) {
                    try {
                        LlmRequest retryRequest = buildRequest(context);
                        LlmResponse retryResponse = callLlmWithRetry(llmPort, retryRequest,
                                providerId, model, context);
                        Duration latency = Duration.between(start, clock.instant());
                        context.setAttribute("llm.response", retryResponse);
                        context.setAttribute("llm.latency", latency);
                        if (retryResponse.hasToolCalls()) {
                            context.setAttribute("llm.toolCalls", retryResponse.getToolCalls());
                        }
                        log.info("[LLM] Retry after truncation succeeded in {}ms", latency.toMillis());
                        if (isEmptyResponse(retryResponse)) {
                            context.setAttribute(ATTR_LLM_ERROR, "LLM returned empty response");
                        }
                        return context;
                    } catch (Exception retryEx) {
                        log.error("[LLM] Retry after truncation also failed: {}", retryEx.getMessage());
                        context.setAttribute(ATTR_LLM_ERROR, retryEx.getMessage());
                        return context;
                    }
                }
            }
            log.error("[LLM] FAILED after {}ms: {}",
                    Duration.between(start, clock.instant()).toMillis(), e.getMessage(), e);
            context.setAttribute(ATTR_LLM_ERROR, e.getMessage());
        }

        return context;
    }

    private LlmResponse callLlmWithRetry(LlmPort llmPort, LlmRequest request,
            String providerId, String model, AgentContext context) throws Exception {
        for (int attempt = 0; attempt <= MAX_EMPTY_RETRIES; attempt++) {
            CompletableFuture<LlmResponse> future = llmPort.chat(request);
            LlmResponse response = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Track usage for every attempt
            if (response.getUsage() != null) {
                LlmUsage usage = response.getUsage();
                usage.setSessionId(context.getSession().getId());
                usageTracker.recordUsage(providerId, model, usage);
                log.debug("[LLM] Usage: {} input, {} output tokens",
                        usage.getInputTokens(), usage.getOutputTokens());
            }

            if (!isEmptyResponse(response) || attempt == MAX_EMPTY_RETRIES) {
                return response;
            }

            log.warn("[LLM] Empty response on attempt {}/{}, retrying...", attempt + 1, MAX_EMPTY_RETRIES + 1);
        }

        // Unreachable, but compiler needs it
        throw new RuntimeException("LLM empty response retries exhausted");
    }

    private boolean isEmptyResponse(LlmResponse response) {
        return !response.hasToolCalls()
                && (response.getContent() == null || response.getContent().isBlank());
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    private LlmRequest buildRequest(AgentContext context) {
        // Select model based on tier from routing
        String modelTier = context.getModelTier();
        ModelSelection selection = selectModel(modelTier);

        log.debug("[LLM] Model tier: {}, selected model: {}, reasoning: {}",
                modelTier != null ? modelTier : "default",
                selection.model,
                selection.reasoning != null ? selection.reasoning : "none");

        // Flatten tool messages on model switch to avoid provider-specific metadata
        // issues
        flattenOnModelSwitch(context, selection.model);

        return LlmRequest.builder()
                .model(selection.model)
                .reasoningEffort(selection.reasoning)
                .systemPrompt(context.getSystemPrompt())
                .messages(context.getMessages())
                .tools(context.getAvailableTools())
                .toolResults(context.getToolResults())
                .sessionId(context.getSession().getId())
                .build();
    }

    /**
     * Detects model switches and flattens tool call messages to plain text when the
     * LLM model changes. This prevents sending provider-specific tool call IDs and
     * metadata to a different provider that may reject them.
     */
    void flattenOnModelSwitch(AgentContext context, String currentModel) {
        java.util.Map<String, Object> metadata = context.getSession().getMetadata();
        String previousModel = metadata != null ? (String) metadata.get(ContextAttributes.LLM_MODEL) : null;

        boolean needsFlatten = false;
        if (previousModel != null && !previousModel.equals(currentModel)) {
            log.info("[LLM] Model switch detected: {} -> {}, flattening tool messages", previousModel, currentModel);
            needsFlatten = true;
        } else if (previousModel == null && hasToolMessages(context.getMessages())) {
            log.info("[LLM] Legacy session with tool messages, flattening for model: {}", currentModel);
            needsFlatten = true;
        }

        if (needsFlatten) {
            List<Message> flattened = Message.flattenToolMessages(context.getMessages());
            context.getMessages().clear();
            context.getMessages().addAll(flattened);

            List<Message> sessionFlattened = Message.flattenToolMessages(context.getSession().getMessages());
            context.getSession().getMessages().clear();
            context.getSession().getMessages().addAll(sessionFlattened);
        }

        // Track current model for next request
        if (metadata != null) {
            metadata.put(ContextAttributes.LLM_MODEL, currentModel);
        }
    }

    private boolean hasToolMessages(List<Message> messages) {
        return messages.stream().anyMatch(m -> m.hasToolCalls() || m.isToolMessage());
    }

    private ModelSelection selectModel(String tier) {
        var router = properties.getRouter();

        return switch (tier != null ? tier : "balanced") {
        case "deep" -> new ModelSelection(router.getDeepModel(), router.getDeepModelReasoning());
        case "coding" -> new ModelSelection(router.getCodingModel(), router.getCodingModelReasoning());
        case "smart" -> new ModelSelection(router.getSmartModel(), router.getSmartModelReasoning());
        default -> new ModelSelection(router.getDefaultModel(), router.getDefaultModelReasoning());
        };
    }

    private record ModelSelection(String model, String reasoning) {
    }

    /**
     * Check if the exception is a context/input length overflow error from the LLM
     * provider.
     */
    boolean isContextOverflowError(Exception e) {
        String message = extractFullMessage(e);
        if (message == null)
            return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("exceeds maximum input length")
                || lower.contains("context_length_exceeded")
                || lower.contains("maximum context length")
                || lower.contains("too many tokens")
                || lower.contains("request too large");
    }

    private String extractFullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t.getMessage() != null)
                sb.append(t.getMessage()).append(" ");
            t = t.getCause();
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Emergency truncation: find messages with content exceeding a per-message
     * limit derived from the model's maxInputTokens, and truncate them. Returns the
     * number of messages truncated.
     */
    int truncateLargeMessages(AgentContext context) {
        String modelTier = context.getModelTier();
        ModelSelection selection = selectModel(modelTier);
        int maxInputTokens = modelConfigService.getMaxInputTokens(selection.model);
        // Each message should use at most 25% of the model's context window
        int maxMessageChars = (int) (maxInputTokens * CHARS_PER_TOKEN * 0.25);
        // Floor: at least 10K chars
        maxMessageChars = Math.max(maxMessageChars, 10000);

        List<Message> messages = context.getMessages();
        int truncated = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getContent() != null && msg.getContent().length() > maxMessageChars) {
                String suffix = "\n\n[EMERGENCY TRUNCATED: " + msg.getContent().length()
                        + " chars total. Try a more specific query to get smaller results.]";
                int cutPoint = Math.max(0, maxMessageChars - suffix.length());
                log.warn("[LLM] Emergency truncating message #{} (role={}, {} chars -> ~{} chars)",
                        i, msg.getRole(), msg.getContent().length(), cutPoint + suffix.length());
                String trimmed = msg.getContent().substring(0, cutPoint) + suffix;
                messages.set(i, Message.builder()
                        .id(msg.getId())
                        .role(msg.getRole())
                        .content(trimmed)
                        .toolCallId(msg.getToolCallId())
                        .toolName(msg.getToolName())
                        .toolCalls(msg.getToolCalls())
                        .timestamp(msg.getTimestamp())
                        .metadata(msg.getMetadata())
                        .build());
                truncated++;
            }
        }

        // Also truncate in session history so it persists
        if (truncated > 0) {
            List<Message> sessionMessages = context.getSession().getMessages();
            for (int i = 0; i < sessionMessages.size(); i++) {
                Message msg = sessionMessages.get(i);
                if (msg.getContent() != null && msg.getContent().length() > maxMessageChars) {
                    String suffix = "\n\n[EMERGENCY TRUNCATED: " + msg.getContent().length()
                            + " chars total. Try a more specific query to get smaller results.]";
                    int cutPoint = Math.max(0, maxMessageChars - suffix.length());
                    sessionMessages.set(i, Message.builder()
                            .id(msg.getId())
                            .role(msg.getRole())
                            .content(msg.getContent().substring(0, cutPoint) + suffix)
                            .toolCallId(msg.getToolCallId())
                            .toolName(msg.getToolName())
                            .toolCalls(msg.getToolCalls())
                            .timestamp(msg.getTimestamp())
                            .metadata(msg.getMetadata())
                            .build());
                }
            }
            log.info("[LLM] Emergency truncation: {} messages trimmed to max ~{} chars each", truncated,
                    maxMessageChars);
        }

        return truncated;
    }
}
