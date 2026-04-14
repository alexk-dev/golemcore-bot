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

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estimates LLM request size for compaction preflight decisions.
 *
 * <p>
 * This intentionally remains provider-agnostic: exact tokenization depends on
 * the vendor/model and request serialization, so this helper uses a
 * conservative per-script characters-per-token heuristic plus per-message /
 * tool overhead. The estimate is meant for safety gating, not billing.
 * </p>
 *
 * <p>
 * Empirical coefficients (averaged across cl100k_base, o200k_base, Claude BPE
 * and Gemini SentencePiece):
 * </p>
 *
 * <ul>
 * <li>Latin / digits / JSON punctuation: ~4.0 chars/token</li>
 * <li>Medium bucket — Cyrillic, Greek, Hebrew, Arabic + Indic and SE Asian
 * scripts (Devanagari, Bengali, Tamil, Thai, Lao, Khmer, …): ~2.0
 * chars/token</li>
 * <li>CJK (Han, Hiragana, Katakana, Hangul): ~1.0 chars/token</li>
 * <li>Anything else (symbols, emoji, misc scripts): ~2.0 chars/token</li>
 * </ul>
 */
public class ContextTokenEstimator {

    private static final double CHARS_PER_TOKEN_LATIN = 4.0d;
    private static final double CHARS_PER_TOKEN_MEDIUM = 2.0d;
    private static final double CHARS_PER_TOKEN_CJK = 1.0d;
    private static final double CHARS_PER_TOKEN_OTHER = 2.0d;

    private static final int MESSAGE_OVERHEAD_TOKENS = 12;
    private static final int TOOL_DEFINITION_OVERHEAD_TOKENS = 96;
    private static final int REQUEST_BASE_OVERHEAD_TOKENS = 256;

    private static final int MAX_NESTED_DEPTH = 32;

    public int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        for (Message message : messages) {
            tokens += estimateMessage(message);
        }
        return saturatingToInt(tokens);
    }

    public int estimateRequest(LlmRequest request) {
        if (request == null) {
            return 0;
        }
        // Mirrors Langchain4jAdapter.convertMessages: only systemPrompt + messages
        // + tool schemas are serialized on the wire. request.toolResults is
        // internal bookkeeping (read by the tool loop for dedupe / plan
        // finalization) and is not sent to providers — counting it inflates
        // the budget and triggers phantom preflight compactions.
        long tokens = REQUEST_BASE_OVERHEAD_TOKENS;
        tokens += estimateText(request.getSystemPrompt());
        tokens += estimateMessages(request.getMessages());
        tokens += estimateTools(request.getTools());
        return saturatingToInt(tokens);
    }

    private int estimateMessage(Message message) {
        if (message == null) {
            return 0;
        }
        long tokens = MESSAGE_OVERHEAD_TOKENS;
        tokens += estimateText(message.getRole());
        tokens += estimateText(message.getContent());
        tokens += estimateText(message.getToolCallId());
        tokens += estimateText(message.getToolName());
        tokens += estimateToolCalls(message.getToolCalls());
        tokens += estimateMetadata(message.getMetadata());
        return saturatingToInt(tokens);
    }

    private int estimateToolCalls(List<Message.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        Set<Object> visited = newVisitedSet();
        for (Message.ToolCall toolCall : toolCalls) {
            if (toolCall == null) {
                continue;
            }
            tokens += estimateText(toolCall.getId());
            tokens += estimateText(toolCall.getName());
            tokens += estimateObjectMap(toolCall.getArguments(), visited, 0);
        }
        return saturatingToInt(tokens);
    }

    private int estimateTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        Set<Object> visited = newVisitedSet();
        for (ToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            tokens += TOOL_DEFINITION_OVERHEAD_TOKENS;
            tokens += estimateText(tool.getName());
            tokens += estimateText(tool.getDescription());
            tokens += estimateObjectMap(tool.getInputSchema(), visited, 0);
        }
        return saturatingToInt(tokens);
    }

    private int estimateMetadata(Map<String, Object> metadata) {
        return estimateObjectMap(metadata, newVisitedSet(), 0);
    }

    private int estimateObjectMap(Map<String, ?> values, Set<Object> visited, int depth) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        if (depth > MAX_NESTED_DEPTH || !visited.add(values)) {
            return 0;
        }
        try {
            long tokens = 2;
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                tokens += estimateText(entry.getKey());
                tokens += estimateObject(entry.getValue(), visited, depth + 1);
            }
            return saturatingToInt(tokens);
        } finally {
            visited.remove(values);
        }
    }

    private int estimateObject(Object value, Set<Object> visited, int depth) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String stringValue) {
            return estimateText(stringValue);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return estimateText(String.valueOf(value));
        }
        if (depth > MAX_NESTED_DEPTH) {
            return 0;
        }
        if (value instanceof Map<?, ?> mapValue) {
            if (!visited.add(mapValue)) {
                return 0;
            }
            try {
                return estimateRawMap(mapValue, visited, depth + 1);
            } finally {
                visited.remove(mapValue);
            }
        }
        if (value instanceof Iterable<?> iterableValue) {
            if (!visited.add(iterableValue)) {
                return 0;
            }
            try {
                return estimateIterable(iterableValue, visited, depth + 1);
            } finally {
                visited.remove(iterableValue);
            }
        }
        if (value.getClass().isArray()) {
            if (!visited.add(value)) {
                return 0;
            }
            try {
                return estimateArray(value, visited, depth + 1);
            } finally {
                visited.remove(value);
            }
        }
        return estimateText(String.valueOf(value));
    }

    private int estimateArray(Object array, Set<Object> visited, int depth) {
        int length = Array.getLength(array);
        if (length == 0) {
            return 2;
        }
        if (array instanceof Object[] objectArray) {
            long tokens = 2;
            for (Object element : objectArray) {
                tokens += estimateObject(element, visited, depth);
            }
            return saturatingToInt(tokens);
        }
        return estimatePrimitiveArray(array, length);
    }

    /**
     * Width-based estimate for primitive arrays — avoids per-element boxing +
     * String.valueOf allocations that would otherwise dominate cost for large
     * numeric buffers in tool arguments.
     */
    private int estimatePrimitiveArray(Object array, int length) {
        double averageCharWidth;
        if (array instanceof boolean[]) {
            averageCharWidth = 5.0d; // "true"/"false" + comma
        } else if (array instanceof byte[]) {
            averageCharWidth = 4.0d; // e.g. "127,"
        } else if (array instanceof short[]) {
            averageCharWidth = 5.0d;
        } else if (array instanceof int[]) {
            averageCharWidth = 6.0d;
        } else if (array instanceof long[]) {
            averageCharWidth = 8.0d;
        } else if (array instanceof float[]) {
            averageCharWidth = 10.0d;
        } else if (array instanceof double[]) {
            averageCharWidth = 12.0d;
        } else if (array instanceof char[] charArray) {
            return saturatingToInt(2 + estimateText(new String(charArray)));
        } else {
            averageCharWidth = 6.0d;
        }
        double totalChars = length * averageCharWidth;
        double tokens = 2 + totalChars / CHARS_PER_TOKEN_LATIN;
        return Math.max(2, (int) Math.ceil(tokens));
    }

    private int estimateRawMap(Map<?, ?> values, Set<Object> visited, int depth) {
        long tokens = 2;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            tokens += estimateObject(entry.getKey(), visited, depth);
            tokens += estimateObject(entry.getValue(), visited, depth);
        }
        return saturatingToInt(tokens);
    }

    private int estimateIterable(Iterable<?> values, Set<Object> visited, int depth) {
        long tokens = 2;
        for (Object value : values) {
            tokens += estimateObject(value, visited, depth);
        }
        return saturatingToInt(tokens);
    }

    private int estimateText(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        double tokens = 0.0d;

        int length = value.length();
        for (int index = 0; index < length;) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint)) {
                tokens += 1.0d / CHARS_PER_TOKEN_LATIN;
                continue;
            }
            // Character.UnicodeScript.of accepts any scalar value returned by
            // String.codePointAt, so no exception handling is needed here.
            tokens += tokenContribution(Character.UnicodeScript.of(codePoint));
        }

        if (tokens <= 0.0d) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private double tokenContribution(Character.UnicodeScript script) {
        return switch (script) {
        case LATIN, COMMON, INHERITED -> 1.0d / CHARS_PER_TOKEN_LATIN;
        case CYRILLIC, GREEK, HEBREW, ARABIC,
                DEVANAGARI, BENGALI, TAMIL, TELUGU, KANNADA, MALAYALAM, GUJARATI, GURMUKHI, ORIYA,
                THAI, LAO, KHMER, MYANMAR ->
            1.0d / CHARS_PER_TOKEN_MEDIUM;
        case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> 1.0d / CHARS_PER_TOKEN_CJK;
        default -> 1.0d / CHARS_PER_TOKEN_OTHER;
        };
    }

    private Set<Object> newVisitedSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private int saturatingToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < 0) {
            return 0;
        }
        return (int) value;
    }
}
