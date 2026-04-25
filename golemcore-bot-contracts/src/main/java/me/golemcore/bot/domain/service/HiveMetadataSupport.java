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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for copying and sanitizing canonical Hive metadata contracts.
 */
public final class HiveMetadataSupport {

    private HiveMetadataSupport() {
    }

    public static void copyMessageMetadataToContext(Message message, AgentContext context) {
        if (message == null || context == null) {
            return;
        }
        copyMetadataMapToContext(message.getMetadata(), context);
    }

    public static void copyMetadataMapToContext(Map<String, Object> metadata, AgentContext context) {
        if (metadata == null || metadata.isEmpty() || context == null) {
            return;
        }
        for (String key : ContextAttributes.HIVE_METADATA_KEYS) {
            String value = readString(metadata, key);
            if (value != null) {
                context.setAttribute(key, value);
            }
        }
    }

    public static void copyContextAttributes(AgentContext context, Map<String, Object> target) {
        if (context == null || target == null) {
            return;
        }
        for (String key : ContextAttributes.HIVE_METADATA_KEYS) {
            Object value = context.getAttribute(key);
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                target.put(key, stringValue);
            }
        }
    }

    public static Map<String, Object> extractContextAttributes(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyContextAttributes(context, metadata);
        return metadata;
    }

    public static void copyMetadataMap(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || source.isEmpty() || target == null) {
            return;
        }
        for (String key : ContextAttributes.HIVE_METADATA_KEYS) {
            putIfPresent(target, key, readString(source, key));
        }
    }

    public static Map<String, Object> stripHiveMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> stripped = new LinkedHashMap<>(metadata);
        for (String key : ContextAttributes.HIVE_METADATA_KEYS) {
            stripped.remove(key);
        }
        return stripped;
    }

    public static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty()) {
            target.put(key, normalized);
        }
    }

    public static String readString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
