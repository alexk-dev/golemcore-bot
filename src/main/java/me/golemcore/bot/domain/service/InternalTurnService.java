package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.port.outbound.InboundMessageDispatchPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Schedules internal runtime-only follow-up turns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalTurnService {

    private static final String INTERNAL_SENDER_ID = "internal:auto-continue";
    private static final String AUTO_CONTINUE_PROMPT = "Continue and finish the previous response. "
            + "This is an internal auto-continue retry after a model failure. "
            + "Use the latest visible user request already in the conversation context. "
            + "Do not ask the user to repeat it unless truly necessary.";

    private final InboundMessageDispatchPort inboundMessageDispatchPort;
    private final Clock clock;

    /**
     * Publish a one-time internal auto-continue retry message for the current
     * session.
     *
     * @return {@code true} when the retry event was published, otherwise
     *         {@code false}
     */
    public boolean scheduleAutoContinueRetry(AgentContext context, String reasonCode) {
        if (context == null || context.getSession() == null) {
            return false;
        }

        AgentSession session = context.getSession();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.MESSAGE_INTERNAL, true);
        metadata.put(ContextAttributes.MESSAGE_INTERNAL_KIND, ContextAttributes.MESSAGE_INTERNAL_KIND_AUTO_CONTINUE);
        metadata.put(ContextAttributes.TURN_QUEUE_KIND, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY);
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

        inboundMessageDispatchPort.dispatch(message);
        log.info("[InternalTurn] scheduled auto-continue retry (sessionId={}, reasonCode={})",
                session.getId(), reasonCode);
        return true;
    }

    private void copyStringAttribute(AgentContext context, Map<String, Object> target, String key) {
        String value = context.getAttribute(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
