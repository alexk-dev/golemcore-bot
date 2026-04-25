package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;

import java.util.Locale;
import java.util.function.Function;

/**
 * Shared JSON extraction and enum/trim helpers for resilience verdict parsers.
 */
public abstract class AbstractClassifierVerdictParser {

    private final TraceSnapshotCodecPort codec;

    protected AbstractClassifierVerdictParser(TraceSnapshotCodecPort codec) {
        this.codec = codec;
    }

    protected final <T, V> V parseResponse(String rawResponse, Class<T> targetType,
            Function<T, V> verdictFactory,
            Function<String, V> failureFactory) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return failureFactory.apply("classifier returned empty response");
        }
        String trimmed = rawResponse.trim();
        String fencedJson = extractFencedJson(trimmed);
        if (fencedJson != null) {
            return decodeAndBuild(fencedJson, targetType, verdictFactory, failureFactory);
        }

        String lastError = null;
        int cursor = 0;
        while (cursor < trimmed.length()) {
            int start = trimmed.indexOf('{', cursor);
            if (start < 0) {
                break;
            }
            int end = findMatchingBrace(trimmed, start);
            if (end < 0) {
                break;
            }
            String candidate = trimmed.substring(start, end + 1);
            try {
                T dto = codec.decodeJson(candidate, targetType);
                if (dto != null) {
                    return verdictFactory.apply(dto);
                }
                lastError = "decoded to null";
            } catch (IllegalArgumentException exception) {
                lastError = exception.getMessage();
            }
            cursor = end + 1;
        }

        if (lastError != null) {
            return failureFactory.apply("classifier response not parseable as JSON: " + lastError);
        }
        return failureFactory.apply("classifier response contained no JSON object");
    }

    protected final <E extends Enum<E>> E resolveEnum(String rawValue, Class<E> enumType, E fallback) {
        if (rawValue == null) {
            return fallback;
        }
        String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    protected final String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private <T, V> V decodeAndBuild(String json, Class<T> targetType, Function<T, V> verdictFactory,
            Function<String, V> failureFactory) {
        T dto;
        try {
            dto = codec.decodeJson(json, targetType);
        } catch (IllegalArgumentException exception) {
            return failureFactory.apply("classifier response not parseable as JSON: " + exception.getMessage());
        }
        if (dto == null) {
            return failureFactory.apply("classifier response decoded to null");
        }
        return verdictFactory.apply(dto);
    }

    private String extractFencedJson(String text) {
        int fenceStart = text.indexOf("```");
        while (fenceStart >= 0) {
            int infoLineEnd = text.indexOf('\n', fenceStart);
            if (infoLineEnd < 0) {
                return null;
            }
            int fenceEnd = text.indexOf("```", infoLineEnd + 1);
            if (fenceEnd < 0) {
                return null;
            }
            String content = text.substring(infoLineEnd + 1, fenceEnd).trim();
            if (!content.isEmpty()) {
                return content;
            }
            fenceStart = text.indexOf("```", fenceEnd + 3);
        }
        return null;
    }

    private int findMatchingBrace(String text, int openIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (current == '\\') {
                    escape = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
}
