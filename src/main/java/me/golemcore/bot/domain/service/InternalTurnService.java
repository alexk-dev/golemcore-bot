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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import org.springframework.context.ApplicationEventPublisher;
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

    private final ApplicationEventPublisher eventPublisher;
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

        eventPublisher.publishEvent(new AgentLoop.InboundMessageEvent(message));
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
