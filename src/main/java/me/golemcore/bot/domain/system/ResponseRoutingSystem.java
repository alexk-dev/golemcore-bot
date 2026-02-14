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
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.service.VoiceResponseHandler.VoiceSendResult;
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
        var transition = context.getSkillTransitionRequest();
        String transitionTarget = transition != null ? transition.targetSkill() : null;
        if (transitionTarget != null) {
            log.debug("[Response] Pipeline transition pending (→ {}), skipping response routing", transitionTarget);
            return context;
        }

        if (isAutoModeMessage(context)) {
            return handleAutoMode(context);
        }

        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        if (outgoing != null) {
            if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
                sendOutgoingText(context, outgoing);
            }
            sendOutgoingVoiceIfRequested(context, outgoing);
            sendOutgoingAttachments(context, outgoing);
            return context;
        }

        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            sendErrorToUser(context, llmError);
            return context;
        }

        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        Boolean voiceRequested = context.getAttribute(ContextAttributes.VOICE_REQUESTED);
        String voiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);

        if (isVoiceOnlyResponse(voiceRequested, voiceText, response)) {
            sendVoiceOnly(context, voiceText);
            return context;
        }

        if (!hasResponseContent(response)) {
            log.debug("[Response] No response content to route (response={}, content={})",
                    response != null ? "present" : "null",
                    response != null && response.getContent() != null ? response.getContent().length() + " chars"
                            : "null");
            return context;
        }

        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return context;
        }

        String content = response.getContent();
        log.info("[Response] Routing {} chars to {}/{}", content.length(),
                session.getChannelType(), session.getChatId());

        if (voiceHandler.isAvailable() && hasVoicePrefix(content)) {
            return handleVoicePrefixResponse(context, session, channel, response);
        }

        return sendTextResponse(context, session, channel, response, content, false);
    }

    // --- Extracted response handlers ---

    private void sendOutgoingText(AgentContext context, OutgoingResponse outgoing) {
        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return;
        }
        String chatId = session.getChatId();
        try {
            channel.sendMessage(chatId, outgoing.getText()).get(30, java.util.concurrent.TimeUnit.SECONDS);
            context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
        } catch (Exception e) {
            log.warn("[Response] FAILED to send (OutgoingResponse): {}", e.getMessage());
            log.debug("[Response] OutgoingResponse send failure", e);
            context.setAttribute(ContextAttributes.ROUTING_ERROR, e.getMessage());
        }
    }

    private AgentContext handleAutoMode(AgentContext context) {
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        return context;
    }

    private AgentContext handleVoicePrefixResponse(AgentContext context, AgentSession session,
            ChannelPort channel, LlmResponse response) {
        String chatId = session.getChatId();
        String prefixVoiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);
        String textToSpeak = (prefixVoiceText != null && !prefixVoiceText.isBlank())
                ? prefixVoiceText
                : stripVoicePrefix(response.getContent());

        if (!textToSpeak.isBlank()) {
            log.debug("[Response] Voice prefix detected, sending voice: {} chars (from {})",
                    textToSpeak.length(), prefixVoiceText != null ? "tool" : "prefix");
            VoiceSendResult voiceResult = voiceHandler.trySendVoice(channel, chatId, textToSpeak);
            if (voiceResult == VoiceSendResult.SUCCESS) {
                context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
                return context;
            }
            if (voiceResult == VoiceSendResult.QUOTA_EXCEEDED) {
                sendVoiceQuotaNotification(channel, chatId);
            }
        }

        // TTS failed or blank — fall through to send text without prefix
        if (textToSpeak.isBlank()) {
            log.debug("[Response] Voice prefix with no content, nothing to send");
            return context;
        }

        log.debug("[Response] TTS skipped/failed, falling back to text: {} chars", textToSpeak.length());
        return sendTextResponse(context, session, channel, response, textToSpeak, false);
    }

    private AgentContext sendTextResponse(AgentContext context, AgentSession session,
            ChannelPort channel, LlmResponse response, String content, boolean skipAssistantHistory) {
        String chatId = session.getChatId();
        String channelType = session.getChannelType();

        try {
            channel.sendMessage(chatId, content).get(30, TimeUnit.SECONDS);
            context.setAttribute(ContextAttributes.RESPONSE_SENT, true);

            log.info("[Response] Sent text to {}/{}", channelType, chatId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Response] FAILED to send (interrupted): {}", e.getMessage());
            log.debug("[Response] Send failure (interrupted)", e);
            context.setAttribute(ContextAttributes.ROUTING_ERROR, e.getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[Response] FAILED to send (timeout): {}", e.getMessage());
            log.debug("[Response] Send failure (timeout)", e);
            context.setAttribute(ContextAttributes.ROUTING_ERROR, e.getMessage());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            log.warn("[Response] FAILED to send: {}", cause.toString());
            log.debug("[Response] Send failure (execution)", e);
            context.setAttribute(ContextAttributes.ROUTING_ERROR, cause.toString());
        } catch (Exception e) {
            log.error("[Response] FAILED to send: {}", e.getMessage(), e);
            context.setAttribute(ContextAttributes.ROUTING_ERROR, e.getMessage());
        }

        sendVoiceAfterText(context, content);

        return context;
    }

    // --- Helper predicates ---

    private boolean hasResponseContent(LlmResponse response) {
        return response != null && response.getContent() != null && !response.getContent().isBlank();
    }

    private boolean isVoiceOnlyResponse(Boolean voiceRequested, String voiceText, LlmResponse response) {
        return Boolean.TRUE.equals(voiceRequested)
                && voiceText != null && !voiceText.isBlank()
                && !hasResponseContent(response);
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

    // --- Channel resolution ---

    private ChannelPort resolveChannel(AgentSession session) {
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel == null) {
            log.warn("[Response] No channel registered for type: {}", session.getChannelType());
        }
        return channel;
    }

    // --- Voice helpers ---

    /**
     * Voice-only path: LLM had no text content, but send_voice tool queued text.
     */
    private void sendVoiceOnly(AgentContext context, String textToSpeak) {
        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return;
        }

        String chatId = session.getChatId();
        VoiceSendResult result = voiceHandler.sendVoiceWithFallback(channel, chatId, textToSpeak);
        if (result != VoiceSendResult.FAILED) {
            context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
        }
        if (result == VoiceSendResult.QUOTA_EXCEEDED) {
            sendVoiceQuotaNotification(channel, chatId);
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
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return;
        }

        String chatId = session.getChatId();
        String voiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);
        String textToSpeak = (voiceText != null && !voiceText.isBlank()) ? voiceText : responseText;

        log.debug("[Response] Sending voice after text: {} chars, chatId={}", textToSpeak.length(), chatId);
        VoiceSendResult result = voiceHandler.trySendVoice(channel, chatId, textToSpeak);
        if (result == VoiceSendResult.QUOTA_EXCEEDED) {
            sendVoiceQuotaNotification(channel, chatId);
        }
    }

    boolean shouldRespondWithVoice(AgentContext context) {
        Boolean voiceRequested = context.getAttribute(ContextAttributes.VOICE_REQUESTED);
        if (Boolean.TRUE.equals(voiceRequested)) {
            return true;
        }
        BotProperties.VoiceProperties voice = properties.getVoice();
        return voice.getTelegram().isRespondWithVoice() && hasIncomingVoice(context);
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

    // --- Quota notification ---

    private void sendVoiceQuotaNotification(ChannelPort channel, String chatId) {
        String message = preferencesService.getMessage("voice.error.quota");
        try {
            channel.sendMessage(chatId, message).get(30, TimeUnit.SECONDS);
            log.info("[Response] Sent voice quota notification to {}", chatId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Response] Failed to send quota notification (interrupted): {}", e.getMessage());
        } catch (Exception e) {
            log.error("[Response] Failed to send quota notification: {}", e.getMessage());
        }
    }

    private void sendOutgoingVoiceIfRequested(AgentContext context, OutgoingResponse outgoing) {
        if (outgoing == null || !outgoing.isVoiceRequested()) {
            return;
        }

        String voiceText = outgoing.getVoiceText();
        String responseText = outgoing.getText();
        String textToSpeak = (voiceText != null && !voiceText.isBlank()) ? voiceText : responseText;
        if (textToSpeak == null || textToSpeak.isBlank()) {
            return;
        }

        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return;
        }

        String chatId = session.getChatId();
        log.debug("[Response] Sending voice for OutgoingResponse: {} chars, chatId={}", textToSpeak.length(), chatId);
        VoiceSendResult result = voiceHandler.trySendVoice(channel, chatId, textToSpeak);
        if (result == VoiceSendResult.QUOTA_EXCEEDED) {
            sendVoiceQuotaNotification(channel, chatId);
        }
    }

    private void sendOutgoingAttachments(AgentContext context, OutgoingResponse outgoing) {
        if (outgoing == null || outgoing.getAttachments() == null || outgoing.getAttachments().isEmpty()) {
            return;
        }

        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return;
        }

        String chatId = session.getChatId();
        for (Attachment attachment : outgoing.getAttachments()) {
            try {
                if (attachment.getType() == Attachment.Type.IMAGE) {
                    channel.sendPhoto(chatId, attachment.getData(), attachment.getFilename(),
                            attachment.getCaption()).get(30, TimeUnit.SECONDS);
                } else {
                    channel.sendDocument(chatId, attachment.getData(), attachment.getFilename(),
                            attachment.getCaption()).get(30, TimeUnit.SECONDS);
                }
                log.debug("[Response] Sent attachment: {} ({} bytes)", attachment.getFilename(),
                        attachment.getData().length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[Response] Failed to send attachment '{}' (interrupted): {}",
                        attachment.getFilename(), e.getMessage());
            } catch (Exception e) {
                log.error("[Response] Failed to send attachment '{}': {}", attachment.getFilename(),
                        e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")

    private void sendErrorToUser(AgentContext context, String error) {
        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return;
        }

        String chatId = session.getChatId();
        String errorMessage = preferencesService.getMessage("system.error.llm");

        log.warn("[Response] Sending LLM error: {}", error);
        try {
            channel.sendMessage(chatId, errorMessage).get(30, TimeUnit.SECONDS);
            context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Response] Failed to send error message (interrupted): {}", e.getMessage());
        } catch (Exception e) {
            log.error("[Response] Failed to send error message: {}", e.getMessage());
        }
    }
    // --- Message helpers ---

    @Override
    public boolean shouldProcess(AgentContext context) {
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        if (outgoing != null) {
            if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
                return true;
            }
            if (outgoing.isVoiceRequested()) {
                return true;
            }
            return outgoing.getAttachments() != null && !outgoing.getAttachments().isEmpty();
        }

        // Legacy path (kept while migration is in progress)
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (hasResponseContent(response)) {
            return true;
        }

        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            return true;
        }

        Boolean voiceRequested = context.getAttribute(ContextAttributes.VOICE_REQUESTED);
        if (Boolean.TRUE.equals(voiceRequested)) {
            return true;
        }

        String voiceText = context.getAttribute(ContextAttributes.VOICE_TEXT);
        if (isVoiceOnlyResponse(voiceRequested, voiceText, response)) {
            return true;
        }

        return false;
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
