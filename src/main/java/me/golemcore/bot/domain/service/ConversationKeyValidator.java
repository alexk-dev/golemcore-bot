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

import java.util.regex.Pattern;

/**
 * Conversation key validation helpers.
 *
 * <p>
 * Strict contract for newly created keys:
 * {@code ^[a-zA-Z0-9_-]{8,64}$}
 *
 * <p>
 * Legacy-compatible contract for reads/switching existing keys:
 * {@code ^[a-zA-Z0-9_-]{1,64}$}
 */
public final class ConversationKeyValidator {

    private static final Pattern STRICT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");
    private static final Pattern LEGACY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private ConversationKeyValidator() {
    }

    public static boolean isStrictConversationKey(String value) {
        String normalized = normalize(value);
        return normalized != null && STRICT_PATTERN.matcher(normalized).matches();
    }

    public static boolean isLegacyCompatibleConversationKey(String value) {
        String normalized = normalize(value);
        return normalized != null && LEGACY_PATTERN.matcher(normalized).matches();
    }

    public static String normalizeStrictOrThrow(String value) {
        String normalized = normalize(value);
        if (normalized == null || !STRICT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("conversationKey must match ^[a-zA-Z0-9_-]{8,64}$");
        }
        return normalized;
    }

    public static String normalizeLegacyCompatibleOrThrow(String value) {
        String normalized = normalize(value);
        if (normalized == null || !LEGACY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("conversationKey must match ^[a-zA-Z0-9_-]{1,64}$");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        return candidate;
    }
}
