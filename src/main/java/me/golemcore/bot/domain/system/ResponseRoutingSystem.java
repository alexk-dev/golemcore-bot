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
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.ChannelPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * System for routing final responses back to the originating channel (order=60,
 * last in pipeline). Looks up channel port by type, sends LLM response or error
 * messages with i18n via {@link service.UserPreferencesService}, sends pending
 * attachments (screenshots, files) queued by ToolExecutionSystem, adds
 * assistant message to session, and marks context as complete. Handles both
 * success and error paths.
 */
@Component
@Slf4j
public class ResponseRoutingSystem implements AgentSystem {

    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();
    private final UserPreferencesService preferencesService;

    public ResponseRoutingSystem(List<ChannelPort> channelPorts, UserPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
        for (ChannelPort port : channelPorts) {
            channelRegistry.put(port.getChannelType(), port);
        }
        log.info("Registered {} channels: {}", channelRegistry.size(), channelRegistry.keySet());
    }

    @Override
    public String getName() {
        return "ResponseRoutingSystem";
    }

    @Override
    public int getOrder() {
        return 60; // Last in the pipeline
    }

    @Override
    public AgentContext process(AgentContext context) {
        // Skip if a pipeline transition is pending — response will be handled in next
        // iteration
        String transitionTarget = context.getAttribute("skill.transition.target");
        if (transitionTarget != null) {
            log.debug("[Response] Pipeline transition pending (→ {}), skipping response routing", transitionTarget);
            return context;
        }

        // Auto-mode: store response in session only, don't send to channel.
        // Milestone notifications are handled separately by GoalManagementTool.
        if (isAutoModeMessage(context)) {
            LlmResponse response = context.getAttribute("llm.response");
            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                context.getSession().addMessage(Message.builder()
                        .role("assistant")
                        .content(response.getContent())
                        .timestamp(Instant.now())
                        .build());
            }
            return context;
        }

        LlmResponse response = context.getAttribute("llm.response");

        // Handle LLM errors — send error message to user
        String llmError = context.getAttribute("llm.error");
        if (llmError != null) {
            sendErrorToUser(context, llmError);
            sendPendingAttachments(context);
            return context;
        }

        if (response == null || response.getContent() == null || response.getContent().isBlank()) {
            log.debug("[Response] No response content to route (response={}, content={})",
                    response != null ? "present" : "null",
                    response != null && response.getContent() != null ? response.getContent().length() + " chars"
                            : "null");
            return context;
        }

        // Don't route if there are pending tool calls
        Boolean toolsExecuted = context.getAttribute("tools.executed");
        if (Boolean.TRUE.equals(toolsExecuted) && response.hasToolCalls()) {
            log.debug("[Response] Tools executed with pending tool calls, waiting for next LLM iteration");
            return context;
        }

        AgentSession session = context.getSession();
        String channelType = session.getChannelType();
        String chatId = session.getChatId();

        ChannelPort channel = channelRegistry.get(channelType);
        if (channel == null) {
            log.error("[Response] Unknown channel type: {}", channelType);
            return context;
        }

        log.debug("[Response] Routing response to channel '{}', chat '{}'", channelType, chatId);
        log.trace("[Response] Content preview: '{}'", truncate(response.getContent(), 150));

        try {
            // Send the response (with timeout to prevent indefinite blocking)
            channel.sendMessage(chatId, response.getContent()).get(30, TimeUnit.SECONDS);

            // Add assistant message to session only if NOT a continuation after tool calls
            // (ToolExecutionSystem already adds assistant message with tool calls)
            if (!Boolean.TRUE.equals(toolsExecuted)) {
                session.addMessage(Message.builder()
                        .role("assistant")
                        .content(response.getContent())
                        .channelType(channelType)
                        .chatId(chatId)
                        .timestamp(Instant.now())
                        .toolCalls(response.getToolCalls())
                        .build());
            } else {
                // After tool execution, add the final assistant message
                session.addMessage(Message.builder()
                        .role("assistant")
                        .content(response.getContent())
                        .channelType(channelType)
                        .chatId(chatId)
                        .timestamp(Instant.now())
                        .build());
            }

            log.debug("[Response] Successfully sent to channel '{}', chat '{}'", channelType, chatId);

        } catch (Exception e) {
            log.error("[Response] FAILED to send: {}", e.getMessage(), e);
            context.setAttribute("routing.error", e.getMessage());
        }

        sendPendingAttachments(context);

        return context;
    }

    @SuppressWarnings("unchecked")
    private void sendPendingAttachments(AgentContext context) {
        List<Attachment> pending = context.getAttribute("pendingAttachments");
        if (pending == null || pending.isEmpty())
            return;

        AgentSession session = context.getSession();
        String channelType = session.getChannelType();
        String chatId = session.getChatId();

        ChannelPort channel = channelRegistry.get(channelType);
        if (channel == null) {
            log.warn("[Response] Cannot send attachments — unknown channel: {}", channelType);
            return;
        }

        for (Attachment attachment : pending) {
            try {
                if (attachment.getType() == Attachment.Type.IMAGE) {
                    channel.sendPhoto(chatId, attachment.getData(),
                            attachment.getFilename(), attachment.getCaption()).get(30, TimeUnit.SECONDS);
                } else {
                    channel.sendDocument(chatId, attachment.getData(),
                            attachment.getFilename(), attachment.getCaption()).get(30, TimeUnit.SECONDS);
                }
                log.debug("[Response] Sent attachment: {} ({} bytes)",
                        attachment.getFilename(), attachment.getData().length);
            } catch (Exception e) {
                log.error("[Response] Failed to send attachment '{}': {}",
                        attachment.getFilename(), e.getMessage());
            }
        }

        context.setAttribute("pendingAttachments", null);
    }

    private void sendErrorToUser(AgentContext context, String error) {
        AgentSession session = context.getSession();
        String channelType = session.getChannelType();
        String chatId = session.getChatId();

        ChannelPort channel = channelRegistry.get(channelType);
        if (channel == null) {
            log.error("[Response] Cannot send error — unknown channel: {}", channelType);
            return;
        }

        String errorMessage = preferencesService.getMessage("system.error.llm");

        log.warn("[Response] Sending LLM error: {}", error);
        try {
            channel.sendMessage(chatId, errorMessage).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Response] Failed to send error message: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        LlmResponse response = context.getAttribute("llm.response");
        String llmError = context.getAttribute("llm.error");
        List<?> pending = context.getAttribute("pendingAttachments");
        return response != null || llmError != null || (pending != null && !pending.isEmpty());
    }

    public void registerChannel(ChannelPort channel) {
        channelRegistry.put(channel.getChannelType(), channel);
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty())
            return false;
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }
}
