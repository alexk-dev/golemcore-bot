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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for session-scoped model tier settings.
 */
public final class SessionModelSettingsSupport {

    private static final Set<String> MODEL_SETTINGS_INHERITANCE_EXCLUDED_CHANNELS = Set.of("judge", "webhook", "hive");

    private SessionModelSettingsSupport() {
    }

    public static boolean shouldInheritModelSettings(String channelType) {
        if (StringValueSupport.isBlank(channelType)) {
            return false;
        }
        String normalizedChannel = channelType.trim().toLowerCase(Locale.ROOT);
        return !MODEL_SETTINGS_INHERITANCE_EXCLUDED_CHANNELS.contains(normalizedChannel);
    }

    public static boolean hasModelSettings(AgentSession session) {
        if (session == null || session.getMetadata() == null) {
            return false;
        }
        Map<String, Object> metadata = session.getMetadata();
        return metadata.containsKey(ContextAttributes.SESSION_MODEL_TIER)
                || metadata.containsKey(ContextAttributes.SESSION_MODEL_TIER_FORCE);
    }

    public static String readModelTier(AgentSession session) {
        if (session == null || session.getMetadata() == null) {
            return null;
        }
        Object value = session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER);
        if (!(value instanceof String tier)) {
            return null;
        }
        String normalizedTier = tier.trim();
        return normalizedTier.isEmpty() ? null : normalizedTier;
    }

    public static boolean readForce(AgentSession session) {
        if (session == null || session.getMetadata() == null) {
            return false;
        }
        Object value = session.getMetadata().get(ContextAttributes.SESSION_MODEL_TIER_FORCE);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    public static void writeModelSettings(AgentSession session, String tier, boolean force) {
        if (session == null) {
            return;
        }
        Map<String, Object> metadata = ensureMetadata(session);
        if (StringValueSupport.isBlank(tier)) {
            metadata.remove(ContextAttributes.SESSION_MODEL_TIER);
        } else {
            metadata.put(ContextAttributes.SESSION_MODEL_TIER, tier.trim());
        }
        metadata.put(ContextAttributes.SESSION_MODEL_TIER_FORCE, force);
    }

    public static void inheritModelSettings(AgentSession source, AgentSession target) {
        if (source == null || target == null || !hasModelSettings(source) || hasModelSettings(target)) {
            return;
        }
        Map<String, Object> sourceMetadata = source.getMetadata();
        Map<String, Object> targetMetadata = ensureMetadata(target);
        copyMetadataValue(sourceMetadata, targetMetadata, ContextAttributes.SESSION_MODEL_TIER);
        copyMetadataValue(sourceMetadata, targetMetadata, ContextAttributes.SESSION_MODEL_TIER_FORCE);
    }

    private static Map<String, Object> ensureMetadata(AgentSession session) {
        if (session.getMetadata() == null) {
            session.setMetadata(new HashMap<>());
        }
        return session.getMetadata();
    }

    private static void copyMetadataValue(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
