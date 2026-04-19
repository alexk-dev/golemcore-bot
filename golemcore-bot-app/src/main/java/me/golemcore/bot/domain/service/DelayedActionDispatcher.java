package me.golemcore.bot.domain.service;

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
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches leased delayed actions through direct delivery or session wake-up.
 */
@Service
public class DelayedActionDispatcher {

    private final SessionRunCoordinator sessionRunCoordinator;
    private final ChannelRuntimePort channelRuntimePort;
    private final ToolArtifactService toolArtifactService;
    private final DelayedActionPolicyService policyService;
    private final Clock clock;

    public DelayedActionDispatcher(SessionRunCoordinator sessionRunCoordinator,
            ChannelRuntimePort channelRuntimePort,
            ToolArtifactService toolArtifactService,
            DelayedActionPolicyService policyService,
            Clock clock) {
        this.sessionRunCoordinator = sessionRunCoordinator;
        this.channelRuntimePort = channelRuntimePort;
        this.toolArtifactService = toolArtifactService;
        this.policyService = policyService;
        this.clock = clock;
    }

    public CompletableFuture<DispatchResult> dispatch(DelayedSessionAction action) {
        if (action == null) {
            return CompletableFuture.completedFuture(DispatchResult.terminal("Delayed action is required"));
        }
        return switch (action.getDeliveryMode()) {
        case DIRECT_MESSAGE -> dispatchDirectMessage(action);
        case DIRECT_FILE -> dispatchDirectFile(action);
        case INTERNAL_TURN -> dispatchInternalTurn(action);
        };
    }

    private CompletableFuture<DispatchResult> dispatchDirectMessage(DelayedSessionAction action) {
        if (!policyService.supportsProactiveMessage(action.getChannelType(), action.getTransportChatId())) {
            return CompletableFuture.completedFuture(proactiveUnavailableResult(
                    action.getChannelType(),
                    "Proactive message delivery unavailable"));
        }
        ChannelDeliveryPort channel = channelRuntimePort.findChannel(action.getChannelType()).orElse(null);
        if (channel == null) {
            return CompletableFuture
                    .completedFuture(DispatchResult.retryable("Channel not found: " + action.getChannelType()));
        }
        String message = payloadString(action, "message");
        if (StringValueSupport.isBlank(message)) {
            return CompletableFuture.completedFuture(DispatchResult.terminal("Direct message payload is empty"));
        }
        return channel.sendMessage(action.getTransportChatId(), message)
                .handle((ignored, failure) -> failure == null
                        ? DispatchResult.completed()
                        : DispatchResult.retryable("Direct message failed: " + failure.getMessage()));
    }

    private CompletableFuture<DispatchResult> dispatchDirectFile(DelayedSessionAction action) {
        if (!policyService.supportsProactiveDocument(action.getChannelType(), action.getTransportChatId())) {
            return CompletableFuture.completedFuture(proactiveUnavailableResult(
                    action.getChannelType(),
                    "Proactive file delivery unavailable"));
        }
        String artifactPath = payloadString(action, "artifactPath");
        if (StringValueSupport.isBlank(artifactPath)) {
            return CompletableFuture.completedFuture(DispatchResult.terminal("Artifact path is required"));
        }
        String caption = payloadString(action, "message");
        String configuredFilename = payloadString(action, "artifactName");
        ChannelDeliveryPort channel = channelRuntimePort.findChannel(action.getChannelType()).orElse(null);
        if (channel == null) {
            return CompletableFuture
                    .completedFuture(DispatchResult.retryable("Channel not found: " + action.getChannelType()));
        }
        ToolArtifactDownload download;
        try {
            download = toolArtifactService.getDownload(artifactPath);
        } catch (RuntimeException e) {
            return CompletableFuture
                    .completedFuture(DispatchResult.terminal("Artifact lookup failed: " + e.getMessage()));
        }
        String filename = !StringValueSupport.isBlank(configuredFilename) ? configuredFilename : download.getFilename();
        CompletableFuture<Void> delivery = download.getMimeType() != null && download.getMimeType().startsWith("image/")
                ? channel.sendPhoto(action.getTransportChatId(), download.getData(), filename, caption)
                : channel.sendDocument(action.getTransportChatId(), download.getData(), filename, caption);
        return delivery.handle((ignored, failure) -> failure == null
                ? DispatchResult.completed()
                : DispatchResult.retryable("File delivery failed: " + failure.getMessage()));
    }

    private CompletableFuture<DispatchResult> dispatchInternalTurn(DelayedSessionAction action) {
        if (!policyService.supportsDelayedExecution(action.getChannelType(), action.getTransportChatId())) {
            return CompletableFuture.completedFuture(proactiveUnavailableResult(
                    action.getChannelType(),
                    "Delayed execution delivery unavailable"));
        }
        Message synthetic = buildInternalMessage(action);
        if (DelayedActionKind.RETRY_LLM_TURN.equals(action.getKind())) {
            return dispatchRetryLlmTurn(synthetic);
        }
        return sessionRunCoordinator.submit(synthetic)
                .handle((ignored, failure) -> failure == null
                        ? DispatchResult.completed()
                        : DispatchResult.retryable("Internal turn failed: " + failure.getMessage()));
    }

    private CompletableFuture<DispatchResult> dispatchRetryLlmTurn(Message synthetic) {
        return sessionRunCoordinator.submitForContext(synthetic)
                .handle((context, failure) -> {
                    if (failure != null) {
                        return DispatchResult.retryable("Internal turn failed: " + failure.getMessage());
                    }
                    if (isTerminalL5Failure(context)) {
                        return DispatchResult.terminal(resolveTerminalL5Reason(context));
                    }
                    return DispatchResult.completed();
                });
    }

    private Message buildInternalMessage(DelayedSessionAction action) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_DELAYED_ACTION);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_DELAYED_ACTION);
        metadata.put(ContextAttributes.CONVERSATION_KEY, action.getConversationKey());
        if (!StringValueSupport.isBlank(action.getTransportChatId())) {
            metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, action.getTransportChatId());
        }
        metadata.put(ContextAttributes.DELAYED_ACTION_ID, action.getId());
        metadata.put(ContextAttributes.DELAYED_ACTION_KIND, action.getKind().name());
        if (action.getRunAt() != null) {
            metadata.put(ContextAttributes.DELAYED_ACTION_RUN_AT, action.getRunAt().toString());
        }
        if (DelayedActionKind.RETRY_LLM_TURN.equals(action.getKind())) {
            metadata.put(ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT, nextResumeAttempt(action));
            putMetadataString(metadata, ContextAttributes.RESILIENCE_L5_ERROR_CODE, payloadString(action, "errorCode"));
            putMetadataString(metadata, ContextAttributes.RESILIENCE_L5_ORIGINAL_PROMPT,
                    payloadString(action, "originalPrompt"));
        }
        metadata = TraceContextSupport.ensureRootMetadata(
                metadata,
                TraceSpanKind.INTERNAL,
                TraceNamingSupport.DELAYED_ACTION);
        return Message.builder()
                .id("delayed-" + action.getId())
                .role("user")
                .content(buildInternalMessageContent(action))
                .channelType(action.getChannelType())
                .chatId(action.getConversationKey())
                .senderId("internal:delayed-action")
                .metadata(metadata)
                .timestamp(Instant.now(clock))
                .build();
    }

    private String buildInternalMessageContent(DelayedSessionAction action) {
        if (DelayedActionKind.RETRY_LLM_TURN.equals(action.getKind())) {
            return buildRetryLlmTurnContent(action);
        }
        String instruction = payloadString(action, "instruction");
        String summary = payloadString(action, "originalSummary");
        StringBuilder builder = new StringBuilder();
        builder.append("[DELAYED ACTION]\n");
        builder.append("Kind: ").append(action.getKind().name()).append('\n');
        if (action.getRunAt() != null) {
            builder.append("Scheduled at: ").append(action.getRunAt()).append('\n');
        }
        if (!StringValueSupport.isBlank(summary)) {
            builder.append("Original request summary: ").append(summary).append('\n');
        }
        if (!StringValueSupport.isBlank(instruction)) {
            builder.append("Instruction: ").append(instruction).append('\n');
        } else if (action.getKind() == DelayedActionKind.NOTIFY_JOB_READY) {
            builder.append(
                    "Instruction: The tracked background job is ready. Inspect the current session context and continue.\n");
        }
        builder.append('\n');
        builder.append("This is a scheduled internal wake-up, not a fresh user message. ");
        builder.append("Use the current session context and continue normally.");
        return builder.toString();
    }

    private String buildRetryLlmTurnContent(DelayedSessionAction action) {
        String errorCode = payloadString(action, "errorCode");
        StringBuilder builder = new StringBuilder();
        builder.append("[DELAYED ACTION]\n");
        builder.append("Kind: ").append(action.getKind().name()).append('\n');
        if (action.getRunAt() != null) {
            builder.append("Scheduled at: ").append(action.getRunAt()).append('\n');
        }
        builder.append("Resume attempt: ").append(nextResumeAttempt(action)).append('\n');
        if (!StringValueSupport.isBlank(errorCode)) {
            builder.append("Last LLM error code: ").append(errorCode).append('\n');
        }
        builder.append("Instruction: Retry the previous suspended user request now.\n\n");
        builder.append("This is a scheduled internal LLM retry, not a fresh user message. ");
        builder.append("Use the latest non-internal user message already present in the conversation history. ");
        builder.append("Do not treat delayed-action metadata as user instructions.");
        return builder.toString();
    }

    private boolean isTerminalL5Failure(AgentContext context) {
        return context != null
                && Boolean.TRUE.equals(context.getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_FAILURE));
    }

    private String resolveTerminalL5Reason(AgentContext context) {
        if (context == null) {
            return "L5 cold retry failed";
        }
        String reason = context.getAttribute(ContextAttributes.RESILIENCE_L5_TERMINAL_REASON);
        return !StringValueSupport.isBlank(reason) ? reason : "L5 cold retry failed";
    }

    private String payloadString(DelayedSessionAction action, String key) {
        if (action == null || action.getPayload() == null) {
            return null;
        }
        Object value = action.getPayload().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue.trim() : null;
    }

    private int nextResumeAttempt(DelayedSessionAction action) {
        return payloadInt(action, "resumeAttempt") + 1;
    }

    private int payloadInt(DelayedSessionAction action, String key) {
        if (action == null || action.getPayload() == null) {
            return 0;
        }
        Object value = action.getPayload().get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(stringValue.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void putMetadataString(Map<String, Object> metadata, String key, String value) {
        if (!StringValueSupport.isBlank(value)) {
            metadata.put(key, value);
        }
    }

    private DispatchResult proactiveUnavailableResult(String channelType, String message) {
        if (!policyService.isChannelSupported(channelType) || !policyService.notificationsEnabled()) {
            return DispatchResult.terminal(message);
        }
        return DispatchResult.retryable(message);
    }

    public record DispatchResult(boolean success, boolean retryable, String error) {
        public static DispatchResult completed() {
            return new DispatchResult(true, false, null);
        }

        public static DispatchResult retryable(String error) {
            return new DispatchResult(false, true, error);
        }

        public static DispatchResult terminal(String error) {
            return new DispatchResult(false, false, error);
        }
    }
}
