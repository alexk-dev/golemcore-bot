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

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.MdcSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.TraceMdcSupport;
import me.golemcore.bot.domain.service.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.VoiceResponseHandler;
import me.golemcore.bot.domain.service.VoiceResponseHandler.VoiceSendResult;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;
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

    private static final String CHANNEL_WEB = "web";
    private static final String CHANNEL_WEBHOOK = "webhook";
    private static final String WEBHOOK_DELIVER_FLAG = "webhook.deliver";
    private static final String WEBHOOK_DELIVER_CHANNEL = "webhook.deliver.channel";
    private static final String WEBHOOK_DELIVER_TO = "webhook.deliver.to";

    private record VoiceRoutingResult(boolean sentVoice, boolean sentTextFallback, String errorMessage) {
        private static VoiceRoutingResult none() {
            return new VoiceRoutingResult(false, false, null);
        }

        private static VoiceRoutingResult voiceSent() {
            return new VoiceRoutingResult(true, false, null);
        }

        private static VoiceRoutingResult textFallback(String errorMessage) {
            return new VoiceRoutingResult(false, errorMessage == null, errorMessage);
        }
    }

    private final ChannelRuntimePort channelRuntimePort;
    private final Map<String, ChannelDeliveryPort> overrides = new ConcurrentHashMap<>();
    private final UserPreferencesService preferencesService;
    private final VoiceResponseHandler voiceHandler;
    private final RuntimeConfigService runtimeConfigService;
    private final TraceService traceService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ResponseRoutingSystem(ChannelRuntimePort channelRuntimePort, UserPreferencesService preferencesService,
            VoiceResponseHandler voiceHandler, RuntimeConfigService runtimeConfigService, TraceService traceService) {
        this.channelRuntimePort = channelRuntimePort;
        this.preferencesService = preferencesService;
        this.voiceHandler = voiceHandler;
        this.runtimeConfigService = runtimeConfigService;
        this.traceService = traceService;
        log.info("Registered {} channels: {}", channelRuntimePort.listChannels().size(),
                channelRuntimePort.listChannels().stream().map(ChannelDeliveryPort::getChannelType).toList());
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

        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);
        TraceContext routingSpan = startRoutingSpan(context);
        captureOutgoingSnapshot(context, routingSpan, tracingConfig, outgoing);

        boolean sentText = false;
        boolean sentVoice = false;
        String errorMessage = null;
        int sentAttachments;

        try (MdcSupport.Scope ignored = MdcSupport.withContext(buildTraceMdcContext(routingSpan, context))) {
            if (shouldSendCombinedWebMessage(context, outgoing)) {
                errorMessage = sendCombinedWebMessage(context, outgoing);
                sentText = errorMessage == null && outgoing.getText() != null && !outgoing.getText().isBlank();
                sentAttachments = errorMessage == null ? toAttachmentMetadata(outgoing.getAttachments()).size() : 0;
            } else {
                if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
                    String textError = sendOutgoingText(context, outgoing);
                    if (textError == null) {
                        sentText = true;
                    } else {
                        errorMessage = textError;
                    }
                }
                sentAttachments = sendOutgoingAttachments(context, outgoing);
            }
            VoiceRoutingResult voiceResult = sendOutgoingVoiceIfRequested(context, outgoing);
            if (voiceResult.sentVoice()) {
                sentVoice = true;
            }
            if (voiceResult.sentTextFallback()) {
                sentText = true;
            }
            if (errorMessage == null) {
                errorMessage = voiceResult.errorMessage();
            }
        }

        RoutingOutcome routingOutcome = RoutingOutcome.builder()
                .attempted(true)
                .sentText(sentText)
                .sentVoice(sentVoice)
                .sentAttachments(sentAttachments)
                .errorMessage(errorMessage)
                .build();
        recordRoutingOutcome(context, routingOutcome);
        finishRoutingSpan(context, routingSpan, errorMessage == null ? TraceStatusCode.OK : TraceStatusCode.ERROR,
                errorMessage);

        return context;
    }

    private TraceContext startRoutingSpan(AgentContext context) {
        if (traceService == null || runtimeConfigService == null || !runtimeConfigService.isTracingEnabled()
                || context == null || context.getSession() == null || context.getTraceContext() == null) {
            return null;
        }
        AgentSession session = context.getSession();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("session.id", session.getId());
        if (session.getChannelType() != null) {
            attributes.put("channel.type", session.getChannelType());
        }
        return traceService.startSpan(session, context.getTraceContext(), "response.route", TraceSpanKind.OUTBOUND,
                java.time.Instant.now(), attributes);
    }

    private void finishRoutingSpan(AgentContext context, TraceContext spanContext, TraceStatusCode statusCode,
            String statusMessage) {
        if (traceService == null || context == null || context.getSession() == null || spanContext == null) {
            return;
        }
        traceService.finishSpan(context.getSession(), spanContext, statusCode, statusMessage, java.time.Instant.now());
    }

    private void captureOutgoingSnapshot(AgentContext context, TraceContext spanContext,
            RuntimeConfig.TracingConfig tracingConfig, OutgoingResponse outgoing) {
        if (traceService == null || context == null || context.getSession() == null || spanContext == null
                || tracingConfig == null || !Boolean.TRUE.equals(tracingConfig.getCaptureOutboundPayloads())) {
            return;
        }
        traceService.captureSnapshot(context.getSession(), spanContext, tracingConfig,
                "response", "application/json", serializeSnapshotPayload(outgoing));
    }

    private Map<String, String> buildTraceMdcContext(TraceContext spanContext, AgentContext context) {
        if (spanContext == null) {
            return Map.of();
        }
        return TraceMdcSupport.buildMdcContext(spanContext, context != null ? context.getAttributes() : Map.of());
    }

    private byte[] serializeSnapshotPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }

    private void recordRoutingOutcome(AgentContext context, RoutingOutcome routingOutcome) {
        TurnOutcome existing = context.getTurnOutcome();
        if (existing != null) {
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

    private String sendOutgoingText(AgentContext context, OutgoingResponse outgoing) {
        AgentSession session = context.getSession();
        ChannelDeliveryPort channel = resolveChannel(session);
        if (channel == null) {
            return null;
        }
        String chatId = SessionIdentitySupport.resolveTransportChatId(session);
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        String errorMessage = sendText(context, channel, chatId, outgoing);
        if (crossChannelDelivery != null) {
            String deliveryError = sendText(context, crossChannelDelivery.channel(), crossChannelDelivery.chatId(),
                    outgoing);
            if (errorMessage == null) {
                errorMessage = deliveryError;
            }
        }
        return errorMessage;
    }

    private boolean shouldSendCombinedWebMessage(AgentContext context, OutgoingResponse outgoing) {
        if (context == null || outgoing == null) {
            return false;
        }
        if ((outgoing.getText() == null || outgoing.getText().isBlank())
                && (outgoing.getAttachments() == null || outgoing.getAttachments().isEmpty())) {
            return false;
        }

        AgentSession session = context.getSession();
        ChannelDeliveryPort primaryChannel = session != null ? resolveChannel(session) : null;
        if (primaryChannel != null && CHANNEL_WEB.equalsIgnoreCase(primaryChannel.getChannelType())) {
            return true;
        }

        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        return crossChannelDelivery != null
                && CHANNEL_WEB.equalsIgnoreCase(crossChannelDelivery.channel().getChannelType());
    }

    private String sendCombinedWebMessage(AgentContext context, OutgoingResponse outgoing) {
        AgentSession session = context.getSession();
        ChannelDeliveryPort channel = resolveChannel(session);
        if (channel == null) {
            return null;
        }
        String chatId = SessionIdentitySupport.resolveTransportChatId(session);
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);

        String errorMessage = null;
        if (CHANNEL_WEB.equalsIgnoreCase(channel.getChannelType())) {
            errorMessage = sendStructuredMessage(channel, buildStructuredWebMessage(chatId, outgoing));
        } else if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
            errorMessage = sendText(context, channel, chatId, outgoing);
        }

        if (crossChannelDelivery != null) {
            String deliveryError = null;
            if (CHANNEL_WEB.equalsIgnoreCase(crossChannelDelivery.channel().getChannelType())) {
                deliveryError = sendStructuredMessage(
                        crossChannelDelivery.channel(),
                        buildStructuredWebMessage(crossChannelDelivery.chatId(), outgoing));
            } else if (outgoing.getText() != null && !outgoing.getText().isBlank()) {
                deliveryError = sendText(context, crossChannelDelivery.channel(), crossChannelDelivery.chatId(),
                        outgoing);
            }
            if (errorMessage == null) {
                errorMessage = deliveryError;
            }
        }
        return errorMessage;
    }

    private String sendText(AgentContext context, ChannelDeliveryPort channel, String chatId,
            OutgoingResponse outgoing) {
        try {
            channel.sendMessage(chatId, outgoing.getText(), buildTransportHints(context, outgoing))
                    .get(30, TimeUnit.SECONDS);
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

    private Map<String, Object> buildTransportHints(AgentContext context, OutgoingResponse outgoing) {
        Map<String, Object> hints = new LinkedHashMap<>();
        if (outgoing != null && outgoing.getHints() != null && !outgoing.getHints().isEmpty()) {
            hints.putAll(outgoing.getHints());
        }
        copyHiveHint(context, hints, ContextAttributes.HIVE_CARD_ID);
        copyHiveHint(context, hints, ContextAttributes.HIVE_THREAD_ID);
        copyHiveHint(context, hints, ContextAttributes.HIVE_COMMAND_ID);
        copyHiveHint(context, hints, ContextAttributes.HIVE_RUN_ID);
        copyHiveHint(context, hints, ContextAttributes.HIVE_GOLEM_ID);
        return hints;
    }

    private void copyHiveHint(AgentContext context, Map<String, Object> hints, String key) {
        if (context == null || hints == null) {
            return;
        }
        Object value = context.getAttribute(key);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            hints.put(key, stringValue);
        }
    }

    private String sendStructuredMessage(ChannelDeliveryPort channel, Message message) {
        try {
            channel.sendMessage(message).get(30, TimeUnit.SECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Response] FAILED to send structured message (interrupted): {}", e.getMessage());
            return e.getMessage();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[Response] FAILED to send structured message: {}", e.getMessage());
            log.debug("[Response] Structured message send failure", e);
            return e.getMessage();
        } catch (Exception e) {
            log.warn("[Response] FAILED to send structured message: {}", e.getMessage());
            log.debug("[Response] Structured message send failure", e);
            return e.getMessage();
        }
    }

    private VoiceRoutingResult sendOutgoingVoiceIfRequested(AgentContext context, OutgoingResponse outgoing) {
        if (outgoing == null || !outgoing.isVoiceRequested()) {
            return VoiceRoutingResult.none();
        }

        String voiceText = outgoing.getVoiceText();
        String responseText = outgoing.getText();
        String textToSpeak = (voiceText != null && !voiceText.isBlank()) ? voiceText : responseText;
        if (textToSpeak == null || textToSpeak.isBlank()) {
            return VoiceRoutingResult.none();
        }

        AgentSession session = context.getSession();
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        ChannelDeliveryPort channel = crossChannelDelivery != null ? crossChannelDelivery.channel()
                : resolveChannel(session);
        if (channel == null) {
            return VoiceRoutingResult.none();
        }

        String chatId = crossChannelDelivery != null
                ? crossChannelDelivery.chatId()
                : SessionIdentitySupport.resolveTransportChatId(session);
        if (!channel.isVoiceResponseEnabled()) {
            if (responseText != null && !responseText.isBlank()) {
                return VoiceRoutingResult.none();
            }
            return VoiceRoutingResult.textFallback(sendVoiceTextFallback(context, channel, chatId, textToSpeak,
                    outgoing));
        }
        log.debug("[Response] Sending voice for OutgoingResponse: {} chars, chatId={}", textToSpeak.length(), chatId);
        VoiceSendResult result = voiceHandler.trySendVoice(channel, chatId, textToSpeak);
        if (result == VoiceSendResult.QUOTA_EXCEEDED) {
            sendVoiceQuotaNotification(channel, chatId);
            return VoiceRoutingResult.none();
        }
        return result == VoiceSendResult.SUCCESS ? VoiceRoutingResult.voiceSent() : VoiceRoutingResult.none();
    }

    private String sendVoiceTextFallback(AgentContext context, ChannelDeliveryPort channel, String chatId,
            String textToSpeak,
            OutgoingResponse outgoing) {
        try {
            channel.sendMessage(chatId, textToSpeak, buildTransportHints(context, outgoing))
                    .get(30, TimeUnit.SECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Response] Voice text fallback interrupted: {}", e.getMessage());
            return e.getMessage();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[Response] Voice text fallback failed: {}", e.getMessage());
            log.debug("[Response] Voice text fallback failure", e);
            return e.getMessage();
        } catch (Exception e) {
            log.warn("[Response] Voice text fallback failed: {}", e.getMessage());
            log.debug("[Response] Voice text fallback failure", e);
            return e.getMessage();
        }
    }

    private int sendOutgoingAttachments(AgentContext context, OutgoingResponse outgoing) {
        if (outgoing == null || outgoing.getAttachments() == null || outgoing.getAttachments().isEmpty()) {
            return 0;
        }

        AgentSession session = context.getSession();
        CrossChannelDelivery crossChannelDelivery = resolveWebhookCrossChannelDelivery(context);
        ChannelDeliveryPort channel = crossChannelDelivery != null ? crossChannelDelivery.channel()
                : resolveChannel(session);
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

    private Message buildStructuredWebMessage(String chatId, OutgoingResponse outgoing) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object model = outgoing.getHints().get("model");
        if (model instanceof String modelValue && !modelValue.isBlank()) {
            metadata.put("model", modelValue);
        }
        Object tier = outgoing.getHints().get("tier");
        if (tier instanceof String tierValue && !tierValue.isBlank()) {
            metadata.put("modelTier", tierValue);
        }
        Object reasoning = outgoing.getHints().get("reasoning");
        if (reasoning instanceof String reasoningValue && !reasoningValue.isBlank()) {
            metadata.put("reasoning", reasoningValue);
        }

        List<Map<String, Object>> attachments = toAttachmentMetadata(outgoing.getAttachments());
        if (!attachments.isEmpty()) {
            metadata.put("attachments", attachments);
        }

        return Message.builder()
                .role("assistant")
                .chatId(chatId)
                .content(outgoing.getText())
                .metadata(metadata)
                .build();
    }

    private List<Map<String, Object>> toAttachmentMetadata(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", attachment.getType() == Attachment.Type.IMAGE ? "image" : "document");
            if (attachment.getFilename() != null && !attachment.getFilename().isBlank()) {
                metadata.put("name", attachment.getFilename());
            }
            if (attachment.getMimeType() != null && !attachment.getMimeType().isBlank()) {
                metadata.put("mimeType", attachment.getMimeType());
            }
            if (attachment.getDownloadUrl() != null && !attachment.getDownloadUrl().isBlank()) {
                metadata.put("url", attachment.getDownloadUrl());
            }
            if (attachment.getInternalFilePath() != null && !attachment.getInternalFilePath().isBlank()) {
                metadata.put("internalFilePath", attachment.getInternalFilePath());
            }
            if (attachment.getThumbnailBase64() != null && !attachment.getThumbnailBase64().isBlank()) {
                metadata.put("thumbnailBase64", attachment.getThumbnailBase64());
            }
            if (attachment.getCaption() != null && !attachment.getCaption().isBlank()) {
                metadata.put("caption", attachment.getCaption());
            }
            if (metadata.containsKey("url") || metadata.containsKey("internalFilePath")) {
                result.add(metadata);
            }
        }
        return result;
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

        ChannelDeliveryPort channel = channelRuntimePort.findChannel(target.channelType()).orElse(null);
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

    private ChannelDeliveryPort resolveChannel(AgentSession session) {
        ChannelDeliveryPort channel = overrides.get(session.getChannelType());
        if (channel == null) {
            channel = channelRuntimePort.findChannel(session.getChannelType()).orElse(null);
        }
        if (channel == null) {
            log.warn("[Response] No channel registered for type: {}", session.getChannelType());
        }
        return channel;
    }

    private void sendVoiceQuotaNotification(ChannelDeliveryPort channel, String chatId) {
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

    public void registerChannel(ChannelDeliveryPort channel) {
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

    private record CrossChannelDelivery(ChannelDeliveryPort channel, String chatId) {
    }
}
