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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.service.VoiceResponseHandler.VoiceSendResult;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * System for routing final responses back to the originating channel (order=60,
 * last in pipeline). Consumes {@link OutgoingResponse} from context attributes
 * as the single transport contract. Sends text, voice (TTS), and attachments
 * (screenshots, files) to the user's channel.
 */
@Component
@Slf4j
public class ResponseRoutingSystem implements AgentSystem {

    private static final String CHANNEL_WEBHOOK = "webhook";
    private static final String WEBHOOK_DELIVER_FLAG = "webhook.deliver";
    private static final String WEBHOOK_DELIVER_CHANNEL = "webhook.deliver.channel";
    private static final String WEBHOOK_DELIVER_TO = "webhook.deliver.to";

    private final ChannelRegistry channelRegistry;
    private final Map<String, ChannelPort> overrides = new ConcurrentHashMap<>();
    private final UserPreferencesService preferencesService;
    private final VoiceResponseHandler voiceHandler;

    public ResponseRoutingSystem(ChannelRegistry channelRegistry, UserPreferencesService preferencesService,
            VoiceResponseHandler voiceHandler) {
        this.channelRegistry = channelRegistry;
        this.preferencesService = preferencesService;
        this.voiceHandler = voiceHandler;
        log.info("Registered {} channels: {}", channelRegistry.getAll().size(),
                channelRegistry.getAll().stream().map(ChannelPort::getChannelType).toList());
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
        SkillTransitionRequest transition = context.getSkillTransitionRequest();
        String transitionTarget = transition != null ? transition.targetSkill() : null;
        if (transitionTarget != null) {
            log.debug("[Response] Pipeline transition pending (-> {}), skipping response routing", transitionTarget);
            return context;
        }

        if (isAutoModeMessage(context)) {
            return context;
        }

        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        if (outgoing == null) {
            log.debug("[Response] No OutgoingResponse present, nothing to route");
            return context;
        }

        // Track routing results for RoutingOutcome
        boolean sentText = false;
        boolean sentVoice = false;
        String errorMessage = null;

        if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
            String textError = sendOutgoingText(context, outgoing);
            if (textError == null) {
                sentText = true;
            } else {
                errorMessage = textError;
            }
        }
        if (sendOutgoingVoiceIfRequested(context, outgoing)) {
            sentVoice = true;
        }
        int sentAttachments = sendOutgoingAttachments(context, outgoing);

        // Build and record RoutingOutcome
        RoutingOutcome routingOutcome = RoutingOutcome.builder()
                .attempted(true)
                .sentText(sentText)
                .sentVoice(sentVoice)
                .sentAttachments(sentAttachments)
                .errorMessage(errorMessage)
                .build();
        recordRoutingOutcome(context, routingOutcome);

        return context;
    }

    private void recordRoutingOutcome(AgentContext context, RoutingOutcome routingOutcome) {
        TurnOutcome existing = context.getTurnOutcome();
        if (existing != null) {
            // Rebuild with routingOutcome added
            TurnOutcome updated = TurnOutcome.builder()
                    .finishReason(existing.getFinishReason())
                    .assistantText(existing.getAssistantText())
                    .outgoingResponse(existing.getOutgoingResponse())
                    .failures(existing.getFailures())
                    .rawHistoryWritten(existing.isRawHistoryWritten())
                    .model(existing.getModel())
                    .autoMode(existing.isAutoMode())
                    .routingOutcome(routingOutcome)
                    .build();
            context.setTurnOutcome(updated);
        }
        context.setAttribute(ContextAttributes.ROUTING_OUTCOME, routingOutcome);
    }

    // --- OutgoingResponse handlers ---

    /**
     * Sends text and returns null on success or error message on failure.
     */
    private String sendOutgoingText(AgentContext context, OutgoingResponse outgoing) {
        AgentSession session = context.getSession();
        ChannelPort channel = resolveChannel(session);
        if (channel == null) {
            return null;
        }
        String chatId = SessionIdentitySupport.resolveTransportChatId(session);
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        String errorMessage = sendText(channel, chatId, outgoing);
        if (crossChannelDelivery != null) {
            String deliveryError = sendText(crossChannelDelivery.channel(), crossChannelDelivery.chatId(), outgoing);
            if (errorMessage == null) {
                errorMessage = deliveryError;
            }
        }
        return errorMessage;
    }

    private String sendText(ChannelPort channel, String chatId, OutgoingResponse outgoing) {
        try {
            channel.sendMessage(chatId, outgoing.getText(), outgoing.getHints()).get(30, TimeUnit.SECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Response] FAILED to send (interrupted): {}", e.getMessage());
            return e.getMessage();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[Response] FAILED to send (OutgoingResponse): {}", e.getMessage());
            log.debug("[Response] OutgoingResponse send failure", e);
            return e.getMessage();
        } catch (Exception e) {
            log.warn("[Response] FAILED to send (OutgoingResponse): {}", e.getMessage());
            log.debug("[Response] OutgoingResponse send failure", e);
            return e.getMessage();
        }
    }

    /**
     * Returns true if voice was successfully sent.
     */
    private boolean sendOutgoingVoiceIfRequested(AgentContext context, OutgoingResponse outgoing) {
        if (outgoing == null || !outgoing.isVoiceRequested()) {
            return false;
        }

        String voiceText = outgoing.getVoiceText();
        String responseText = outgoing.getText();
        String textToSpeak = (voiceText != null && !voiceText.isBlank()) ? voiceText : responseText;
        if (textToSpeak == null || textToSpeak.isBlank()) {
            return false;
        }

        AgentSession session = context.getSession();
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        ChannelPort channel = crossChannelDelivery != null ? crossChannelDelivery.channel() : resolveChannel(session);
        if (channel == null) {
            return false;
        }

        String chatId = crossChannelDelivery != null
                ? crossChannelDelivery.chatId()
                : SessionIdentitySupport.resolveTransportChatId(session);
        log.debug("[Response] Sending voice for OutgoingResponse: {} chars, chatId={}", textToSpeak.length(), chatId);
        VoiceSendResult result = voiceHandler.trySendVoice(channel, chatId, textToSpeak);
        if (result == VoiceSendResult.QUOTA_EXCEEDED) {
            sendVoiceQuotaNotification(channel, chatId);
            return false;
        }
        return result == VoiceSendResult.SUCCESS;
    }

    /**
     * Returns count of successfully sent attachments.
     */
    private int sendOutgoingAttachments(AgentContext context, OutgoingResponse outgoing) {
        if (outgoing == null || outgoing.getAttachments() == null || outgoing.getAttachments().isEmpty()) {
            return 0;
        }

        AgentSession session = context.getSession();
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        ChannelPort channel = crossChannelDelivery != null ? crossChannelDelivery.channel() : resolveChannel(session);
        if (channel == null) {
            return 0;
        }

        int sent = 0;
        String chatId = crossChannelDelivery != null
                ? crossChannelDelivery.chatId()
                : SessionIdentitySupport.resolveTransportChatId(session);
        for (Attachment attachment : outgoing.getAttachments()) {
            try {
                if (attachment.getType() == Attachment.Type.IMAGE) {
                    channel.sendPhoto(chatId, attachment.getData(), attachment.getFilename(),
                            attachment.getCaption()).get(30, TimeUnit.SECONDS);
                } else {
                    channel.sendDocument(chatId, attachment.getData(), attachment.getFilename(),
                            attachment.getCaption()).get(30, TimeUnit.SECONDS);
                }
                sent++;
                log.debug("[Response] Sent attachment: {} ({} bytes)", attachment.getFilename(),
                        attachment.getData().length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Response] Failed to send attachment '{}' (interrupted): {}",
                        attachment.getFilename(), e.getMessage());
                return sent;
            } catch (Exception e) {
                log.error("[Response] Failed to send attachment '{}': {}", attachment.getFilename(),
                        e.getMessage());
            }
        }
        return sent;
    }

    private CrossChannelDelivery resolveWebhookCrossChannelDelivery(AgentContext context) {
        AgentSession session = context.getSession();
        if (session == null || !CHANNEL_WEBHOOK.equalsIgnoreCase(session.getChannelType())) {
            return null;
        }

        WebhookDeliveryTarget target = findWebhookDeliveryTarget(context.getMessages());
        if (target == null) {
            target = findWebhookDeliveryTarget(session.getMessages());
        }
        if (target == null) {
            return null;
        }

        ChannelPort channel = channelRegistry.get(target.channelType()).orElse(null);
        if (channel == null) {
            log.warn("[Response] Webhook delivery target channel is not registered: {}", target.channelType());
            return null;
        }

        return new CrossChannelDelivery(channel, target.chatId());
    }

    private WebhookDeliveryTarget findWebhookDeliveryTarget(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        WebhookDeliveryTarget deliveryTarget = null;
        boolean webhookDeliveryCandidateSeen = false;
        for (int index = messages.size() - 1; index >= 0 && !webhookDeliveryCandidateSeen; index--) {
            Message message = messages.get(index);
            if (message == null || !message.isUserMessage()) {
                continue;
            }

            Map<String, Object> metadata = message.getMetadata();
            if (!isWebhookDeliveryEnabled(metadata)) {
                continue;
            }
            webhookDeliveryCandidateSeen = true;

            String channelType = readMetadataString(metadata, WEBHOOK_DELIVER_CHANNEL);
            String chatId = readMetadataString(metadata, WEBHOOK_DELIVER_TO);
            if (channelType == null || chatId == null) {
                continue;
            }

            String normalizedChannel = channelType.trim().toLowerCase(Locale.ROOT);
            if (normalizedChannel.isEmpty() || CHANNEL_WEBHOOK.equals(normalizedChannel)) {
                continue;
            }

            deliveryTarget = new WebhookDeliveryTarget(normalizedChannel, chatId);
        }

        return deliveryTarget;
    }

    private boolean isWebhookDeliveryEnabled(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }

        Object value = metadata.get(WEBHOOK_DELIVER_FLAG);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    private String readMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }

        Object value = metadata.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }

        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    // --- Channel resolution ---

    private ChannelPort resolveChannel(AgentSession session) {
        ChannelPort channel = overrides.get(session.getChannelType());
        if (channel == null) {
            channel = channelRegistry.get(session.getChannelType()).orElse(null);
        }
        if (channel == null) {
            log.warn("[Response] No channel registered for type: {}", session.getChannelType());
        }
        return channel;
    }

    // --- Quota notification ---

    private void sendVoiceQuotaNotification(ChannelPort channel, String chatId) {
        String message = preferencesService.getMessage("voice.error.quota");
        try {
            channel.sendMessage(chatId, message).get(30, TimeUnit.SECONDS);
            log.info("[Response] Sent voice quota notification to {}", chatId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Response] Failed to send quota notification (interrupted): {}", e.getMessage());
            return;
        } catch (Exception e) {
            log.error("[Response] Failed to send quota notification: {}", e.getMessage());
        }
    }

    // --- shouldProcess ---

    @Override
    public boolean shouldProcess(AgentContext context) {
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        if (outgoing == null) {
            return false;
        }
        if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
            return true;
        }
        if (outgoing.isVoiceRequested()) {
            return true;
        }
        return outgoing.getAttachments() != null && !outgoing.getAttachments().isEmpty();
    }

    public void registerChannel(ChannelPort channel) {
        overrides.put(channel.getChannelType(), channel);
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private record WebhookDeliveryTarget(String channelType, String chatId) {
    }

    private record CrossChannelDelivery(ChannelPort channel, String chatId) {
    }
}
