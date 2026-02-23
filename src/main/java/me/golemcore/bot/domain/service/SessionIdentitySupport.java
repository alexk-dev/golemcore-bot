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

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.SessionIdentity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Helpers for resolving and persisting transport/session identity fields.
 */
public final class SessionIdentitySupport {

    private static final String SESSION_ID_SEPARATOR = ":";

    private SessionIdentitySupport() {
    }

    public static String resolveConversationKey(AgentSession session) {
        if (session == null) {
            return "";
        }

        String metadataConversation = readMetadataString(session, ContextAttributes.CONVERSATION_KEY);
        if (!StringValueSupport.isBlank(metadataConversation)) {
            return metadataConversation;
        }

        String sessionId = session.getId();
        if (!StringValueSupport.isBlank(sessionId)) {
            int index = sessionId.indexOf(SESSION_ID_SEPARATOR);
            if (index >= 0 && index + 1 < sessionId.length()) {
                return sessionId.substring(index + 1);
            }
        }

        if (!StringValueSupport.isBlank(session.getChatId())) {
            return session.getChatId();
        }
        return "";
    }

    public static SessionIdentity resolveSessionIdentity(AgentSession session) {
        if (session == null) {
            return null;
        }
        return resolveSessionIdentity(session.getChannelType(), resolveConversationKey(session));
    }

    public static SessionIdentity resolveSessionIdentity(String channelType, String conversationKey) {
        if (StringValueSupport.isBlank(channelType) || StringValueSupport.isBlank(conversationKey)) {
            return null;
        }
        String normalizedChannel = channelType.trim().toLowerCase(Locale.ROOT);
        String normalizedConversationKey;
        try {
            normalizedConversationKey = ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(conversationKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new SessionIdentity(normalizedChannel, normalizedConversationKey);
    }

    public static String resolveTransportChatId(AgentSession session) {
        if (session == null) {
            return "";
        }

        String metadataTransport = readMetadataString(session, ContextAttributes.TRANSPORT_CHAT_ID);
        if (!StringValueSupport.isBlank(metadataTransport)) {
            return metadataTransport;
        }

        if (!StringValueSupport.isBlank(session.getChatId())) {
            return session.getChatId();
        }
        return "";
    }

    public static boolean belongsToTransport(AgentSession session, String transportChatId) {
        if (StringValueSupport.isBlank(transportChatId)) {
            return false;
        }
        return transportChatId.equals(resolveTransportChatId(session));
    }

    public static boolean bindTransportAndConversation(
            AgentSession session,
            String transportChatId,
            String conversationKey) {
        if (session == null) {
            return false;
        }

        if (session.getMetadata() == null) {
            session.setMetadata(new HashMap<>());
        }

        Map<String, Object> metadata = session.getMetadata();
        boolean changed = false;

        if (!StringValueSupport.isBlank(transportChatId)
                && !transportChatId.equals(readMetadataString(session, ContextAttributes.TRANSPORT_CHAT_ID))) {
            metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
            changed = true;
        }
        if (!StringValueSupport.isBlank(conversationKey)
                && !conversationKey.equals(readMetadataString(session, ContextAttributes.CONVERSATION_KEY))) {
            metadata.put(ContextAttributes.CONVERSATION_KEY, conversationKey);
            changed = true;
        }
        return changed;
    }

    public static String readMetadataString(AgentSession session, String key) {
        if (session == null || session.getMetadata() == null || StringValueSupport.isBlank(key)) {
            return null;
        }
        Object value = session.getMetadata().get(key);
        if (value instanceof String) {
            String stringValue = ((String) value).trim();
            return stringValue.isEmpty() ? null : stringValue;
        }
        return null;
    }
}
