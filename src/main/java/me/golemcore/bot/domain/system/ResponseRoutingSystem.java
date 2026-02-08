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
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.bot.voice.TelegramVoiceHandler;
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

    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();
    private final UserPreferencesService preferencesService;
    private final VoicePort voicePort;
    private final TelegramVoiceHandler voiceHandler;
    private final BotProperties properties;

    public ResponseRoutingSystem(List<ChannelPort> channelPorts, UserPreferencesService preferencesService,
            VoicePort voicePort, TelegramVoiceHandler voiceHandler, BotProperties properties) {
        this.preferencesService = preferencesService;
        this.voicePort = voicePort;
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

        // Voice-only response: send_voice tool provided text, LLM had no text content
        Boolean voiceRequested = context.getAttribute("voiceRequested");
        String voiceText = context.getAttribute("voiceText");
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

        log.info("[Response] Routing {} chars to {}/{}", response.getContent().length(), channelType, chatId);

        String content = response.getContent();

        // Voice prefix detection: if response starts with 🔊, send voice instead of
        // text
        if (voicePort.isAvailable() && hasVoicePrefix(content)) {
            // Prefer voiceText from send_voice tool if set (has the real content)
            String prefixVoiceText = context.getAttribute("voiceText");
            String textToSpeak = (prefixVoiceText != null && !prefixVoiceText.isBlank())
                    ? prefixVoiceText
                    : stripVoicePrefix(content);
            log.info("[Response] Voice prefix detected, sending voice: {} chars (from {})",
                    textToSpeak.length(), prefixVoiceText != null ? "tool" : "prefix");
            if (sendVoiceInsteadOfText(context, channel, chatId, textToSpeak, toolsExecuted)) {
                sendPendingAttachments(context);
                return context;
            }
            // TTS failed — fall through to send text without prefix
            content = textToSpeak;
            log.info("[Response] TTS failed, falling back to text: {} chars", content.length());
        }

        try {
            // Send the response (with timeout to prevent indefinite blocking)
            channel.sendMessage(chatId, content).get(30, TimeUnit.SECONDS);

            // Add assistant message to session only if NOT a continuation after tool calls
            // (ToolExecutionSystem already adds assistant message with tool calls)
            if (!Boolean.TRUE.equals(toolsExecuted)) {
                session.addMessage(Message.builder()
                        .role("assistant")
                        .content(content)
                        .channelType(channelType)
                        .chatId(chatId)
                        .timestamp(Instant.now())
                        .toolCalls(response.getToolCalls())
                        .build());
            } else {
                // After tool execution, add the final assistant message
                session.addMessage(Message.builder()
                        .role("assistant")
                        .content(content)
                        .channelType(channelType)
                        .chatId(chatId)
                        .timestamp(Instant.now())
                        .build());
            }

            log.info("[Response] Sent text to {}/{}", channelType, chatId);

        } catch (Exception e) {
            log.error("[Response] FAILED to send: {}", e.getMessage(), e);
            context.setAttribute("routing.error", e.getMessage());
        }

        sendPendingAttachments(context);
        sendVoiceIfRequested(context, content);

        return context;
    }

    static final String VOICE_PREFIX = "\uD83D\uDD0A";

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
     * Synthesize TTS and send voice message instead of text. Returns true if
     * successful.
     */
    private boolean sendVoiceInsteadOfText(AgentContext context, ChannelPort channel,
            String chatId, String textToSpeak, Boolean toolsExecuted) {
        AgentSession session = context.getSession();
        try {
            byte[] audioData = voiceHandler.synthesizeForTelegram(textToSpeak).get(60, TimeUnit.SECONDS);
            channel.sendVoice(chatId, audioData).get(30, TimeUnit.SECONDS);

            // Add clean text (without prefix) to session history
            session.addMessage(Message.builder()
                    .role("assistant")
                    .content(textToSpeak)
                    .channelType(session.getChannelType())
                    .chatId(chatId)
                    .timestamp(Instant.now())
                    .build());

            log.info("[Response] Voice sent: {} bytes audio, chatId={}", audioData.length, chatId);
            return true;
        } catch (Exception e) {
            log.error("[Response] Voice prefix TTS failed, will fall back to text: {}", e.getMessage(), e);
            return false;
        }
    }

    private void sendVoiceIfRequested(AgentContext context, String responseText) {
        boolean available = voicePort.isAvailable();
        Boolean voiceRequested = context.getAttribute("voiceRequested");
        boolean incomingVoice = hasIncomingVoice(context);
        boolean autoRespond = properties.getVoice().getTelegram().isRespondWithVoice();

        log.info("[Response] Voice check: available={}, toolRequested={}, incomingVoice={}, autoRespond={}",
                available, Boolean.TRUE.equals(voiceRequested), incomingVoice, autoRespond);

        if (!available) {
            if (properties.getVoice().isEnabled()) {
                log.warn("[Response] Voice ENABLED but not available — check ELEVENLABS_API_KEY");
            }
            return;
        }

        if (!shouldRespondWithVoice(context)) {
            log.info("[Response] Voice not triggered");
            return;
        }

        AgentSession session = context.getSession();
        String chatId = session.getChatId();
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel == null) {
            log.warn("[Response] Voice skipped: no channel for type '{}'", session.getChannelType());
            return;
        }

        // Use specific voice text if set by VoiceResponseTool, otherwise use full
        // response
        String voiceText = context.getAttribute("voiceText");
        String textToSpeak = (voiceText != null && !voiceText.isBlank()) ? voiceText : responseText;

        log.info("[Response] Sending voice response: {} chars to synthesize, chatId={}",
                textToSpeak.length(), chatId);

        try {
            byte[] audioData = voiceHandler.synthesizeForTelegram(textToSpeak).get(60, TimeUnit.SECONDS);
            channel.sendVoice(chatId, audioData).get(30, TimeUnit.SECONDS);
            log.info("[Response] Voice response sent: {} bytes MP3, chatId={}", audioData.length, chatId);
        } catch (Exception e) {
            log.error("[Response] Failed to send voice response to chatId={}: {}", chatId, e.getMessage(), e);
        }
    }

    private void sendVoiceOnly(AgentContext context, String textToSpeak) {
        AgentSession session = context.getSession();
        String chatId = session.getChatId();
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel == null) {
            log.warn("[Response] Voice-only skipped: no channel for '{}'", session.getChannelType());
            return;
        }

        if (!voicePort.isAvailable()) {
            log.warn("[Response] Voice-only fallback to text: voice not available");
            try {
                channel.sendMessage(chatId, textToSpeak).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("[Response] Failed to send fallback text: {}", e.getMessage());
            }
            addAssistantMessage(session, textToSpeak);
            return;
        }

        log.info("[Response] Voice-only: synthesizing {} chars", textToSpeak.length());
        try {
            byte[] audioData = voiceHandler.synthesizeForTelegram(textToSpeak).get(60, TimeUnit.SECONDS);
            channel.sendVoice(chatId, audioData).get(30, TimeUnit.SECONDS);
            log.info("[Response] Voice-only sent: {} bytes audio", audioData.length);
        } catch (Exception e) {
            log.error("[Response] Voice-only TTS failed, falling back to text: {}", e.getMessage());
            try {
                channel.sendMessage(chatId, textToSpeak).get(30, TimeUnit.SECONDS);
            } catch (Exception e2) {
                log.error("[Response] Fallback text also failed: {}", e2.getMessage());
            }
        }
        addAssistantMessage(session, textToSpeak);
    }

    private void addAssistantMessage(AgentSession session, String content) {
        session.addMessage(Message.builder()
                .role("assistant")
                .content(content)
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .timestamp(Instant.now())
                .build());
    }

    boolean shouldRespondWithVoice(AgentContext context) {
        // Explicitly requested by VoiceResponseTool
        Boolean voiceRequested = context.getAttribute("voiceRequested");
        if (Boolean.TRUE.equals(voiceRequested)) {
            log.info("[Response] Voice triggered: VoiceResponseTool");
            return true;
        }

        // Incoming voice message + config allows auto-respond
        if (properties.getVoice().getTelegram().isRespondWithVoice() && hasIncomingVoice(context)) {
            log.info("[Response] Voice triggered: incoming voice message");
            return true;
        }

        return false;
    }

    private boolean hasIncomingVoice(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        // Check the last user message for voice data
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage()) {
                return msg.hasVoice();
            }
        }
        return false;
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
        Boolean voiceRequested = context.getAttribute("voiceRequested");
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
