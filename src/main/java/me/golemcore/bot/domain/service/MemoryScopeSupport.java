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
import me.golemcore.bot.domain.model.AutoRunKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared helpers for memory scope normalization and storage-path mapping.
 */
public final class MemoryScopeSupport {

    public static final String GLOBAL_SCOPE = "global";
    public static final String SESSION_PREFIX = "session:";
    public static final String GOAL_PREFIX = "goal:";
    public static final String TASK_PREFIX = "task:";

    private static final Pattern CHANNEL_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");

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

        if (candidate.startsWith(SESSION_PREFIX)) {
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

        if (candidate.startsWith(GOAL_PREFIX)) {
            String[] parts = candidate.split(":", 4);
            if (parts.length != 4) {
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

            String goalId = normalizeToken(parts[3]);
            if (goalId == null) {
                return GLOBAL_SCOPE;
            }

            return GOAL_PREFIX + normalizedChannel + ":" + conversationKey + ":" + goalId;
        }

        if (candidate.startsWith(TASK_PREFIX)) {
            String[] parts = candidate.split(":", 2);
            if (parts.length != 2) {
                return GLOBAL_SCOPE;
            }
            String taskId = normalizeToken(parts[1]);
            if (taskId == null) {
                return GLOBAL_SCOPE;
            }
            return TASK_PREFIX + taskId;
        }

        return GLOBAL_SCOPE;
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

    public static String buildGoalScopeOrGlobal(String channelType, String conversationKey, String goalId) {
        String sessionScope = buildSessionScopeOrGlobal(channelType, conversationKey);
        if (GLOBAL_SCOPE.equals(sessionScope)) {
            return GLOBAL_SCOPE;
        }
        String normalizedGoalId = normalizeToken(goalId);
        if (normalizedGoalId == null) {
            return GLOBAL_SCOPE;
        }
        String[] parts = sessionScope.split(":", 3);
        return GOAL_PREFIX + parts[1] + ":" + parts[2] + ":" + normalizedGoalId;
    }

    public static String buildTaskScopeOrGlobal(String taskId) {
        String normalizedTaskId = normalizeToken(taskId);
        if (normalizedTaskId == null) {
            return GLOBAL_SCOPE;
        }
        return TASK_PREFIX + normalizedTaskId;
    }

    /**
     * Resolve canonical memory scope for a session, or {@code global} if session
     * identity is invalid.
     */
    public static String resolveScopeFromSessionOrGlobal(AgentSession session) {
        if (session == null) {
            return GLOBAL_SCOPE;
        }
        String conversationKey = SessionIdentitySupport.resolveConversationKey(session);
        return buildSessionScopeOrGlobal(session.getChannelType(), conversationKey);
    }

    public static List<String> resolveScopeChain(
            AgentSession session,
            String runKind,
            String goalId,
            String taskId) {
        String sessionScope = resolveScopeFromSessionOrGlobal(session);
        return buildScopeChain(sessionScope, runKind, goalId, taskId);
    }

    public static List<String> buildScopeChain(
            String sessionScope,
            String runKind,
            String goalId,
            String taskId) {
        String normalizedSessionScope = normalizeScopeOrGlobal(sessionScope);
        String taskScope = buildTaskScopeOrGlobal(taskId);
        String goalScope = GLOBAL_SCOPE;

        if (normalizedSessionScope.startsWith(SESSION_PREFIX)) {
            String[] parts = normalizedSessionScope.split(":", 3);
            goalScope = buildGoalScopeOrGlobal(parts[1], parts[2], goalId);
        }

        Set<String> chain = new LinkedHashSet<>();
        AutoRunKind autoRunKind = parseRunKind(runKind);
        if (autoRunKind == AutoRunKind.GOAL_RUN) {
            addIfScoped(chain, taskScope);
            addIfScoped(chain, goalScope);
            addIfScoped(chain, normalizedSessionScope);
            chain.add(GLOBAL_SCOPE);
            return new ArrayList<>(chain);
        }

        if (autoRunKind == AutoRunKind.TASK_RUN) {
            addIfScoped(chain, taskScope);
            addIfScoped(chain, normalizedSessionScope);
            chain.add(GLOBAL_SCOPE);
            return new ArrayList<>(chain);
        }

        addIfScoped(chain, normalizedSessionScope);
        chain.add(GLOBAL_SCOPE);
        return new ArrayList<>(chain);
    }

    public static boolean isSessionScope(String scope) {
        return normalizeScopeOrGlobal(scope).startsWith(SESSION_PREFIX);
    }

    public static boolean isGoalScope(String scope) {
        return normalizeScopeOrGlobal(scope).startsWith(GOAL_PREFIX);
    }

    public static boolean isTaskScope(String scope) {
        return normalizeScopeOrGlobal(scope).startsWith(TASK_PREFIX);
    }

    public static String toStoragePrefix(String scope) {
        String normalized = normalizeScopeOrGlobal(scope);
        if (normalized.startsWith(SESSION_PREFIX)) {
            String[] parts = normalized.split(":", 3);
            if (parts.length != 3) {
                return "";
            }
            return "scopes/session/" + parts[1] + "/" + parts[2] + "/";
        }

        if (normalized.startsWith(GOAL_PREFIX)) {
            String[] parts = normalized.split(":", 4);
            if (parts.length != 4) {
                return "";
            }
            return "scopes/goal/" + parts[1] + "/" + parts[2] + "/" + parts[3] + "/";
        }

        if (normalized.startsWith(TASK_PREFIX)) {
            String[] parts = normalized.split(":", 2);
            if (parts.length != 2) {
                return "";
            }
            return "scopes/task/" + parts[1] + "/";
        }

        return "";
    }

    private static void addIfScoped(Set<String> chain, String scope) {
        String normalized = normalizeScopeOrGlobal(scope);
        if (GLOBAL_SCOPE.equals(normalized)) {
            return;
        }
        chain.add(normalized);
    }

    private static AutoRunKind parseRunKind(String runKind) {
        if (StringValueSupport.isBlank(runKind)) {
            return null;
        }
        try {
            return AutoRunKind.valueOf(runKind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static String normalizeToken(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        String candidate = value.trim();
        if (!TOKEN_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }
}
