package me.golemcore.bot.domain.scheduling;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.service.TraceNamingSupport;
import me.golemcore.bot.domain.service.TraceContextSupport;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schedules internal runtime-only follow-up turns.
 */
@Service
@Slf4j
public class InternalTurnService {

    private static final String INTERNAL_SENDER_ID = "internal:auto-continue";
    private static final String FOLLOW_THROUGH_SENDER_ID = "internal:follow-through";
    private static final String AUTO_PROCEED_SENDER_ID = "internal:auto-proceed";
    private static final String AUTO_CONTINUE_PROMPT = "Continue and finish the previous response. "
            + "This is an internal auto-continue retry after a model failure. "
            + "Use the latest visible user request already in the conversation context. "
            + "Do not ask the user to repeat it unless truly necessary.";
    private static final String SAFE_FOLLOW_THROUGH_PROMPT = "Continue with the concrete next action you just promised, using only the latest user request and visible conversation context. Do not broaden scope. If the action is destructive, asks for credentials, sends external messages, modifies production, or is ambiguous, ask the real user for confirmation.";
    private static final String SAFE_AUTO_PROCEED_PROMPT = "Proceed with the single non-destructive next step you just asked to continue.";

    private final InboundMessageDispatchPort inboundMessageDispatchPort;
    private final Clock clock;

    public InternalTurnService(
            InboundMessageDispatchPort inboundMessageDispatchPort,
            Clock clock) {
        this.inboundMessageDispatchPort = inboundMessageDispatchPort;
        this.clock = clock;
    }

    public boolean scheduleAutoContinueRetry(AgentContext context, String reasonCode) {
        if (context == null || context.getSession() == null) {
            return false;
        }

        AgentSession session = context.getSession();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_MODEL_RETRY);
        copyStringAttribute(context, metadata, ContextAttributes.TRANSPORT_CHAT_ID);
        copyStringAttribute(context, metadata, ContextAttributes.CONVERSATION_KEY);
        copyStringAttribute(context, metadata, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        metadata = TraceContextSupport.ensureRootMetadata(
                metadata,
                TraceSpanKind.INTERNAL,
                TraceNamingSupport.INTERNAL_AUTO_CONTINUE);

        Message message = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(AUTO_CONTINUE_PROMPT)
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .senderId(INTERNAL_SENDER_ID)
                .metadata(metadata)
                .timestamp(clock.instant())
                .build();

        try {
            inboundMessageDispatchPort.dispatch(message);
            log.info("[InternalTurn] scheduled auto-continue retry (sessionId={}, reasonCode={})",
                    session.getId(), reasonCode);
            return true;
        } catch (RuntimeException exception) {
            log.warn("[InternalTurn] failed to schedule auto-continue retry (sessionId={}): {}",
                    session.getId(), exception.getMessage());
            return false;
        }
    }

    /**
     * Publish a follow-through continuation nudge on behalf of the resilience
     * follow-through classifier.
     */
    public boolean scheduleFollowThroughNudge(AgentContext context, String continuationPrompt, int previousChainDepth) {
        if (context == null || context.getSession() == null) {
            return false;
        }
        if (continuationPrompt == null || continuationPrompt.isBlank()) {
            return false;
        }

        AgentSession session = context.getSession();
        int nextChainDepth = Math.max(0, previousChainDepth) + 1;
        long baselineRealUserActivitySequence = resolveBaselineRealUserActivitySequence(context);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_FOLLOW_THROUGH_NUDGE);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_FOLLOW_THROUGH);
        metadata.put(ContextAttributes.RESILIENCE_FOLLOW_THROUGH_CHAIN_DEPTH, nextChainDepth);
        metadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, baselineRealUserActivitySequence);
        copyStringAttribute(context, metadata, ContextAttributes.TRANSPORT_CHAT_ID);
        copyStringAttribute(context, metadata, ContextAttributes.CONVERSATION_KEY);
        copyStringAttribute(context, metadata, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        metadata = TraceContextSupport.ensureRootMetadata(
                metadata,
                TraceSpanKind.INTERNAL,
                TraceNamingSupport.RESILIENCE_FOLLOW_THROUGH_NUDGE);

        Message message = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(SAFE_FOLLOW_THROUGH_PROMPT)
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .senderId(FOLLOW_THROUGH_SENDER_ID)
                .metadata(metadata)
                .timestamp(clock.instant())
                .build();

        try {
            inboundMessageDispatchPort.dispatch(message);
            log.info("[InternalTurn] scheduled follow-through nudge (sessionId={}, chainDepth={})",
                    session.getId(), nextChainDepth);
            return true;
        } catch (RuntimeException exception) {
            log.warn("[InternalTurn] failed to schedule follow-through nudge (sessionId={}): {}",
                    session.getId(), exception.getMessage());
            return false;
        }
    }

    /**
     * Publish an Auto-Proceed affirmation on behalf of the resilience classifier.
     */
    public boolean scheduleAutoProceedAffirmation(AgentContext context, String affirmationPrompt,
            int previousChainDepth) {
        if (context == null || context.getSession() == null) {
            return false;
        }
        if (affirmationPrompt == null || affirmationPrompt.isBlank()) {
            return false;
        }

        AgentSession session = context.getSession();
        int nextChainDepth = Math.max(0, previousChainDepth) + 1;
        long baselineRealUserActivitySequence = resolveBaselineRealUserActivitySequence(context);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND,
                ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_PROCEED);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_AUTO_PROCEED);
        metadata.put(ContextAttributes.RESILIENCE_AUTO_PROCEED_CHAIN_DEPTH, nextChainDepth);
        metadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, baselineRealUserActivitySequence);
        copyStringAttribute(context, metadata, ContextAttributes.TRANSPORT_CHAT_ID);
        copyStringAttribute(context, metadata, ContextAttributes.CONVERSATION_KEY);
        copyStringAttribute(context, metadata, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        metadata = TraceContextSupport.ensureRootMetadata(
                metadata,
                TraceSpanKind.INTERNAL,
                TraceNamingSupport.RESILIENCE_AUTO_PROCEED_AFFIRMATION);

        Message message = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(SAFE_AUTO_PROCEED_PROMPT)
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .senderId(AUTO_PROCEED_SENDER_ID)
                .metadata(metadata)
                .timestamp(clock.instant())
                .build();

        try {
            inboundMessageDispatchPort.dispatch(message);
            log.info("[InternalTurn] scheduled auto-proceed affirmation (sessionId={}, chainDepth={})",
                    session.getId(), nextChainDepth);
            return true;
        } catch (RuntimeException exception) {
            log.warn("[InternalTurn] failed to schedule auto-proceed affirmation (sessionId={}): {}",
                    session.getId(), exception.getMessage());
            return false;
        }
    }

    private long resolveBaselineRealUserActivitySequence(AgentContext context) {
        Message lastUserMessage = findLastUserMessage(context);
        if (lastUserMessage == null || lastUserMessage.getMetadata() == null) {
            return 0L;
        }
        Object raw = lastUserMessage.getMetadata().get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE);
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return 0L;
    }

    private Message findLastUserMessage(AgentContext context) {
        List<Message> messages = context != null ? context.getMessages() : null;
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                return message;
            }
        }
        return null;
    }

    private void copyStringAttribute(AgentContext context, Map<String, Object> target, String key) {
        String value = context.getAttribute(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
