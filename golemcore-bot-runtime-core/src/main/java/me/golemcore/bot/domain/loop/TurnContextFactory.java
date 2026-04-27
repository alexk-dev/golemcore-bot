package me.golemcore.bot.domain.loop;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.HiveMetadataSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.TraceContextSupport;
import me.golemcore.bot.domain.service.TraceMdcSupport;
import me.golemcore.bot.domain.service.TraceNamingSupport;
import me.golemcore.bot.domain.service.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;

/**
 * Builds the immutable parts of a turn before the system pipeline starts.
 */
final class TurnContextFactory {

    private final RuntimeConfigService runtimeConfigService;
    private final TraceService traceService;
    private final Clock clock;
    private final TraceSnapshotCodecPort traceSnapshotCodecPort;

    TurnContextFactory(RuntimeConfigService runtimeConfigService, TraceService traceService, Clock clock,
            TraceSnapshotCodecPort traceSnapshotCodecPort) {
        this.runtimeConfigService = runtimeConfigService;
        this.traceService = traceService;
        this.clock = clock;
        this.traceSnapshotCodecPort = Objects.requireNonNull(traceSnapshotCodecPort,
                "traceSnapshotCodecPort must not be null");
    }

    Message prepareMessage(Message message) {
        message.setMetadata(TraceContextSupport.ensureRootMetadata(message.getMetadata(),
                message.isInternalMessage() || AutoRunContextSupport.isAutoMessage(message) ? TraceSpanKind.INTERNAL
                        : TraceSpanKind.INGRESS,
                TraceNamingSupport.inboundMessage(message)));
        return message;
    }

    Map<String, String> buildMdcContext(Message message) {
        Map<String, String> mdcContext = new LinkedHashMap<>(AutoRunContextSupport.buildMdcContext(message));
        mdcContext.putAll(TraceMdcSupport.buildMdcContext(message));
        return mdcContext;
    }

    PreparedTurn create(AgentSession session, Message message) {
        TraceContext rootTraceContext = initializeRootTrace(session, message);
        applySessionIdentityMetadata(session, message);
        if (!message.isInternalMessage()) {
            session.addMessage(message);
        }

        captureInboundSnapshot(session, rootTraceContext, message);

        AgentContext context = AgentContext.builder().session(session).messages(buildContextMessages(session, message))
                .maxIterations(resolveMaxSkillTransitions()).currentIteration(0).build();
        context.setTraceContext(rootTraceContext);
        applyRuntimeAttributes(context, message, session);
        return new PreparedTurn(context, rootTraceContext);
    }

    private void applySessionIdentityMetadata(AgentSession session, Message message) {
        if (session == null || message == null) {
            return;
        }

        String conversationKey = readMetadataString(message, ContextAttributes.CONVERSATION_KEY);
        if (conversationKey == null || conversationKey.isBlank()) {
            conversationKey = message.getChatId();
        }

        String transportChatId = readMetadataString(message, ContextAttributes.TRANSPORT_CHAT_ID);
        if (transportChatId == null || transportChatId.isBlank()) {
            transportChatId = message.getChatId();
        }

        if (conversationKey == null || conversationKey.isBlank() || transportChatId == null
                || transportChatId.isBlank()) {
            return;
        }

        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        String webClientInstanceId = readMetadataString(message, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        if (message.getChannelType() != null && ChannelTypes.WEB.equalsIgnoreCase(message.getChannelType())
                && webClientInstanceId != null && !webClientInstanceId.isBlank()) {
            SessionIdentitySupport.bindWebClientInstance(session, webClientInstanceId);
        }
    }

    private String resolveTransportChatId(Message message) {
        String transportChatId = readMetadataString(message, ContextAttributes.TRANSPORT_CHAT_ID);
        if (transportChatId != null && !transportChatId.isBlank()) {
            return transportChatId;
        }
        return message != null ? message.getChatId() : null;
    }

    private void applyRuntimeAttributes(AgentContext context, Message message, AgentSession session) {
        if (context == null) {
            return;
        }

        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(session);
        if (sessionIdentity != null) {
            context.setAttribute(ContextAttributes.SESSION_IDENTITY_CHANNEL, sessionIdentity.channelType());
            context.setAttribute(ContextAttributes.SESSION_IDENTITY_CONVERSATION, sessionIdentity.conversationKey());
        }

        String transportChatId = resolveTransportChatId(message);
        if (transportChatId != null && !transportChatId.isBlank()) {
            context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
        }
        if (sessionIdentity != null && sessionIdentity.conversationKey() != null) {
            context.setAttribute(ContextAttributes.CONVERSATION_KEY, sessionIdentity.conversationKey());
        }
        String webClientInstanceId = readMetadataString(message, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        if ((webClientInstanceId == null || webClientInstanceId.isBlank()) && session != null) {
            webClientInstanceId = SessionIdentitySupport.resolveWebClientInstanceId(session);
        }
        if (webClientInstanceId != null && !webClientInstanceId.isBlank()) {
            context.setAttribute(ContextAttributes.WEB_CLIENT_INSTANCE_ID, webClientInstanceId);
        }

        if (AutoRunContextSupport.isAutoMessage(message)) {
            context.setAttribute(ContextAttributes.AUTO_MODE, true);
        }
        if (message != null && message.isInternalMessage()) {
            context.setAttribute(ContextAttributes.TURN_INPUT_INTERNAL, true);
        }
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_RUN_KIND);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_RUN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_SCHEDULE_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_GOAL_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_TASK_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_SCHEDULED_TASK_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.ACTIVE_SKILL_NAME);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_REFLECTION_TIER);
        copyStringMetadataAttribute(message, context, ContextAttributes.WEBHOOK_MODEL_TIER);
        copyMetadataAttribute(message, context, ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA);
        copyStringMetadataAttribute(message, context, ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT);
        copyStringMetadataAttribute(message, context, ContextAttributes.WEBHOOK_RESPONSE_VALIDATION_MODEL_TIER);
        copyStringMetadataAttribute(message, context, ContextAttributes.MEMORY_PRESET_ID);
        HiveMetadataSupport.copyMessageMetadataToContext(message, context);
        copyStringMetadataAttribute(message, context, ContextAttributes.DELAYED_ACTION_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.DELAYED_ACTION_KIND);
        copyStringMetadataAttribute(message, context, ContextAttributes.DELAYED_ACTION_RUN_AT);
        copyMetadataAttribute(message, context, ContextAttributes.RESILIENCE_L5_RESUME_ATTEMPT);
        copyStringMetadataAttribute(message, context, ContextAttributes.RESILIENCE_L5_ERROR_CODE);
        copyStringMetadataAttribute(message, context, ContextAttributes.RESILIENCE_L5_ORIGINAL_PROMPT);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_SPAN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_PARENT_SPAN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_ROOT_KIND);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_NAME);
        TraceContext traceContext = TraceContextSupport
                .readTraceContext(message != null ? message.getMetadata() : null);
        if (traceContext != null) {
            context.setTraceContext(traceContext);
        }

        boolean reflectionActive = readMetadataBoolean(message, ContextAttributes.AUTO_REFLECTION_ACTIVE);
        if (reflectionActive) {
            context.setAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }

        if (message != null && message.getMetadata() != null
                && message.getMetadata().containsKey(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY)) {
            boolean reflectionTierPriority = readMetadataBoolean(message,
                    ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
            context.setAttribute(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, reflectionTierPriority);
        }
    }

    private void copyStringMetadataAttribute(Message message, AgentContext context, String key) {
        String value = readMetadataString(message, key);
        if (value == null || value.isBlank()) {
            return;
        }
        context.setAttribute(key, value);
    }

    private void copyMetadataAttribute(Message message, AgentContext context, String key) {
        if (message == null || message.getMetadata() == null || context == null || key == null || key.isBlank()) {
            return;
        }
        if (message.getMetadata().containsKey(key)) {
            context.setAttribute(key, message.getMetadata().get(key));
        }
    }

    private List<Message> buildContextMessages(AgentSession session, Message inbound) {
        List<Message> messages = new ArrayList<>();
        if (session != null && session.getMessages() != null) {
            messages.addAll(session.getMessages());
        }
        if (inbound != null && inbound.isInternalMessage()) {
            messages.add(inbound);
        }
        return messages;
    }

    private String readMetadataString(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return null;
        }
        return AutoRunContextSupport.readMetadataString(message.getMetadata(), key);
    }

    private boolean readMetadataBoolean(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return false;
        }
        Object value = message.getMetadata().get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    private TraceContext initializeRootTrace(AgentSession session, Message message) {
        if (!runtimeConfigService.isTracingEnabled() || session == null || message == null) {
            return TraceContextSupport.readTraceContext(message != null ? message.getMetadata() : null);
        }
        TraceContext seededContext = TraceContextSupport.readTraceContext(message.getMetadata());
        TraceSpanKind spanKind = message.isInternalMessage() || AutoRunContextSupport.isAutoMessage(message)
                ? TraceSpanKind.INTERNAL
                : TraceSpanKind.INGRESS;
        String traceName = TraceContextSupport.readTraceName(message.getMetadata());
        if (traceName == null || traceName.isBlank()) {
            traceName = TraceNamingSupport.inboundMessage(message);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("session.id", session.getId());
        if (message.getChannelType() != null) {
            attributes.put("channel.type", message.getChannelType());
        }
        if (message.getChatId() != null) {
            attributes.put("chat.id", message.getChatId());
        }
        if (message.getSenderId() != null) {
            attributes.put("sender.id", message.getSenderId());
        }
        return traceService.startRootTrace(session, seededContext, traceName, spanKind,
                message.getTimestamp() != null ? message.getTimestamp() : clock.instant(), attributes,
                runtimeConfigService.getTraceMaxTracesPerSession());
    }

    private void captureInboundSnapshot(AgentSession session, TraceContext rootTraceContext, Message message) {
        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);
        if (session == null || rootTraceContext == null || tracingConfig == null
                || !Boolean.TRUE.equals(tracingConfig.getCaptureInboundPayloads())) {
            return;
        }
        traceService.captureSnapshot(session, rootTraceContext, tracingConfig, "request", "application/json",
                serializeSnapshotPayload(message));
    }

    private byte[] serializeSnapshotPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        return traceSnapshotCodecPort.encodeJson(payload);
    }

    private int resolveMaxSkillTransitions() {
        int maxSkillTransitions = runtimeConfigService.getTurnMaxSkillTransitions();
        if (maxSkillTransitions > 0) {
            return maxSkillTransitions;
        }
        return runtimeConfigService.getTurnMaxLlmCalls();
    }

    record PreparedTurn(AgentContext context, TraceContext rootTraceContext) {
    }
}
