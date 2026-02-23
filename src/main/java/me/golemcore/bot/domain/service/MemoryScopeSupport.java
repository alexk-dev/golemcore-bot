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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared helpers for Memory V2 scope normalization and storage-path mapping.
 */
public final class MemoryScopeSupport {

    public static final String GLOBAL_SCOPE = "global";
    private static final String SESSION_PREFIX = "session:";
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private MemoryScopeSupport() {
    }

    public static String normalizeScopeOrGlobal(String scope) {
        if (StringValueSupport.isBlank(scope)) {
            return GLOBAL_SCOPE;
        }
        String candidate = scope.trim();
        if (GLOBAL_SCOPE.equalsIgnoreCase(candidate)) {
            return GLOBAL_SCOPE;
        }
        if (!candidate.startsWith(SESSION_PREFIX)) {
            return GLOBAL_SCOPE;
        }

        String[] parts = candidate.split(":", 3);
        if (parts.length != 3) {
            return GLOBAL_SCOPE;
        }

        String normalizedChannel = normalizeChannel(parts[1]);
        if (normalizedChannel == null) {
            return GLOBAL_SCOPE;
        }

        String conversationKey;
        try {
            conversationKey = ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(parts[2]);
        } catch (IllegalArgumentException e) {
            return GLOBAL_SCOPE;
        }

        return SESSION_PREFIX + normalizedChannel + ":" + conversationKey;
    }

    public static String buildSessionScopeOrGlobal(String channelType, String conversationKey) {
        String normalizedChannel = normalizeChannel(channelType);
        if (normalizedChannel == null) {
            return GLOBAL_SCOPE;
        }

        String normalizedConversation;
        try {
            normalizedConversation = ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(conversationKey);
        } catch (IllegalArgumentException e) {
            return GLOBAL_SCOPE;
        }

        return SESSION_PREFIX + normalizedChannel + ":" + normalizedConversation;
    }

    /**
     * Resolve canonical memory scope for a session, or {@code global} if session identity is invalid.
     */
    public static String resolveScopeFromSessionOrGlobal(AgentSession session) {
        if (session == null) {
            return GLOBAL_SCOPE;
        }
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        return buildSessionScopeOrGlobal(session.getChannelType(), conversationKey);
    }

    public static boolean isSessionScope(String scope) {
        return normalizeScopeOrGlobal(scope).startsWith(SESSION_PREFIX);
    }

    public static String toStoragePrefix(String scope) {
        String normalized = normalizeScopeOrGlobal(scope);
        if (!normalized.startsWith(SESSION_PREFIX)) {
            return "";
        }

        String[] parts = normalized.split(":", 3);
        if (parts.length != 3) {
            return "";
        }

        return "scopes/session/" + parts[1] + "/" + parts[2] + "/";
    }

    private static String normalizeChannel(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        if (!CHANNEL_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }
}
