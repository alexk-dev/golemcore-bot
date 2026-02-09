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
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.infrastructure.config.BotProperties;
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
 * messages with i18n via {@link UserPreferencesService}, sends pending
 * attachments (screenshots, files) queued by ToolExecutionSystem, optionally
 * sends voice response via TTS, adds assistant message to session, and marks
 * context as complete. Handles both success and error paths.
 */
@Component
@Slf4j
public class ResponseRoutingSystem implements AgentSystem {

    static final String VOICE_PREFIX = "\uD83D\uDD0A";

    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();
    private final UserPreferencesService preferencesService;
    private final VoiceResponseHandler voiceHandler;
    private final BotProperties properties;

    public ResponseRoutingSystem(List<ChannelPort> channelPorts, UserPreferencesService preferencesService,
            VoiceResponseHandler voiceHandler, BotProperties properties) {
        this.preferencesService = preferencesService;
        this.voiceHandler = voiceHandler;
        this.properties = properties;
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
        if (isAutoModeMessage(context)) {
            LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                context.getSession().addMessage(Message.builder()
                        .role("assistant")
                        .content(response.getContent())
                        .timestamp(Instant.now())
                        .build());
            }
            return context;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);

        // Handle LLM errors — send error message to user
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            sendErrorToUser(context, llmError);
            sendPendingAttachments(context);
            return context;
        }

        // Voice-only response: send_voice tool provided text, LLM had no text content
        Boolean voiceRequested = context.getAttribute(ContextAttributes.VOICE_REQUESTED);
        String voiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);
        if (Boolean.TRUE.equals(voiceRequested) && voiceText != null && !voiceText.isBlank()
                && (response == null || response.getContent() == null || response.getContent().isBlank())) {
            sendVoiceOnly(context, voiceText);
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
        Boolean toolsExecuted = context.getAttribute(ContextAttributes.TOOLS_EXECUTED);
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

        log.info("[Response] Routing {} chars to {}/{}", response.getContent().length(), channelType, chatId);

        String content = response.getContent();

        // Voice prefix detection: if response starts with 🔊, send voice instead of
        // text
        if (voiceHandler.isAvailable() && hasVoicePrefix(content)) {
            String prefixVoiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);
            String textToSpeak = (prefixVoiceText != null && !prefixVoiceText.isBlank())
                    ? prefixVoiceText
                    : stripVoicePrefix(content);
            // Skip TTS for blank text (e.g. "🔊 " with no actual content)
            if (!textToSpeak.isBlank()) {
                log.debug("[Response] Voice prefix detected, sending voice: {} chars (from {})",
                        textToSpeak.length(), prefixVoiceText != null ? "tool" : "prefix");
                if (voiceHandler.trySendVoice(channel, chatId, textToSpeak)) {
                    addAssistantMessage(session, textToSpeak, response.getToolCalls());
                    sendPendingAttachments(context);
                    return context;
                }
            }
            // TTS failed or blank — fall through to send text without prefix
            content = textToSpeak;
            if (content.isBlank()) {
                log.debug("[Response] Voice prefix with no content, nothing to send");
                sendPendingAttachments(context);
                return context;
            }
            log.debug("[Response] TTS skipped/failed, falling back to text: {} chars", content.length());
        }

        try {
            // Send the response (with timeout to prevent indefinite blocking)
            channel.sendMessage(chatId, content).get(30, TimeUnit.SECONDS);

            // Add assistant message to session only if NOT a continuation after tool calls
            // (ToolExecutionSystem already adds assistant message with tool calls)
            if (!Boolean.TRUE.equals(toolsExecuted)) {
                addAssistantMessage(session, content, response.getToolCalls());
            } else {
                addAssistantMessage(session, content, null);
            }

            log.info("[Response] Sent text to {}/{}", channelType, chatId);

        } catch (Exception e) {
            log.error("[Response] FAILED to send: {}", e.getMessage(), e);
            context.setAttribute("routing.error", e.getMessage());
        }

        sendPendingAttachments(context);
        sendVoiceAfterText(context, content);

        return context;
    }

    boolean hasVoicePrefix(String content) {
        return content != null && content.trim().startsWith(VOICE_PREFIX);
    }

    String stripVoicePrefix(String content) {
        if (content == null)
            return "";
        String trimmed = content.trim();
        if (trimmed.startsWith(VOICE_PREFIX)) {
            return trimmed.substring(VOICE_PREFIX.length()).trim();
        }
        return trimmed;
    }

    /**
     * Voice-only path: LLM had no text content, but send_voice tool queued text.
     */
    private void sendVoiceOnly(AgentContext context, String textToSpeak) {
        AgentSession session = context.getSession();
        String chatId = session.getChatId();
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel == null) {
            log.warn("[Response] Voice-only skipped: no channel for '{}'", session.getChannelType());
            return;
        }

        boolean sent = voiceHandler.sendVoiceWithFallback(channel, chatId, textToSpeak);
        if (sent) {
            addAssistantMessage(session, textToSpeak, null);
        }
    }

    /**
     * Voice-after-text path: text was already sent, optionally send voice too.
     */
    private void sendVoiceAfterText(AgentContext context, String responseText) {
        if (!shouldRespondWithVoice(context)) {
            log.debug("[Response] Voice not triggered");
            return;
        }

        AgentSession session = context.getSession();
        String chatId = session.getChatId();
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel == null) {
            return;
        }

        String voiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);
        String textToSpeak = (voiceText != null && !voiceText.isBlank()) ? voiceText : responseText;

        log.debug("[Response] Sending voice after text: {} chars, chatId={}", textToSpeak.length(), chatId);
        voiceHandler.trySendVoice(channel, chatId, textToSpeak);
    }

    boolean shouldRespondWithVoice(AgentContext context) {
        Boolean voiceRequested = context.getAttribute(ContextAttributes.VOICE_REQUESTED);
        if (Boolean.TRUE.equals(voiceRequested)) {
            return true;
        }
        if (properties.getVoice().getTelegram().isRespondWithVoice() && hasIncomingVoice(context)) {
            return true;
        }
        return false;
    }

    private boolean hasIncomingVoice(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage()) {
                return msg.hasVoice();
            }
        }
        return false;
    }

    private void addAssistantMessage(AgentSession session, String content, List<Message.ToolCall> toolCalls) {
        session.addMessage(Message.builder()
                .role("assistant")
                .content(content)
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .timestamp(Instant.now())
                .toolCalls(toolCalls)
                .build());
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

    @Override
    public boolean shouldProcess(AgentContext context) {
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        List<?> pending = context.getAttribute("pendingAttachments");
        Boolean voiceRequested = context.getAttribute(ContextAttributes.VOICE_REQUESTED);
        return response != null || llmError != null || (pending != null && !pending.isEmpty())
                || Boolean.TRUE.equals(voiceRequested);
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
