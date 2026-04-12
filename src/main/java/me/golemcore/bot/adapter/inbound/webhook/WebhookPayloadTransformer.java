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

package me.golemcore.bot.adapter.inbound.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms arbitrary JSON payloads into message strings using templates with
 * {@code {json.path}} placeholders.
 *
 * <p>
 * Example template: {@code "Push to {repository.name} by {pusher.name}"}
 * applied to a GitHub push event extracts nested JSON values via JSONPath-style
 * traversal from the payload root.
 */
@Component
@Slf4j
public class WebhookPayloadTransformer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Applies the template to the raw JSON payload, resolving all
     * {@code {json.path}} placeholders.
     *
     * @param template
     *            message template with placeholders
     * @param rawBody
     *            raw JSON bytes from the incoming request
     * @return resolved message string, with unresolved placeholders replaced by
     *         {@code <missing>}
     */
    public String transform(String template, byte[] rawBody) {
        if (template == null || template.isBlank()) {
            return new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
        }

        DocumentContext context;
        try {
            Object jsonDocument = objectMapper.readValue(rawBody, Object.class);
            context = JsonPath.parse(jsonDocument);
        } catch (Exception e) {
            log.warn("[Webhook] Failed to parse JSON payload for template resolution: {}", e.getMessage());
            return template;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            String value = resolveJsonPath(context, path);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveJsonPath(DocumentContext context, String path) {
        if (path == null || path.isBlank()) {
            return "<missing>";
        }
        String normalizedPath = normalizeJsonPath(path);
        try {
            Object resolved = context.read(normalizedPath);
            if (resolved == null) {
                return "null";
            }
            if (resolved instanceof String stringValue) {
                return stringValue;
            }
            return objectMapper.writeValueAsString(resolved);
        } catch (PathNotFoundException e) {
            return "<missing>";
        } catch (InvalidPathException e) {
            log.debug("[Webhook] Invalid JSONPath '{}': {}", normalizedPath, e.getMessage());
            return "<missing>";
        } catch (Exception e) {
            log.warn("[Webhook] Failed to render JSONPath '{}': {}", normalizedPath, e.getMessage());
            return "<missing>";
        }
    }

    private String normalizeJsonPath(String path) {
        if (path == null || path.isBlank()) {
            return "$";
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("$")) {
            return trimmed;
        }
        return "$" + buildBracketPath(trimmed);
    }

    private String buildBracketPath(String path) {
        StringBuilder result = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        int bracketDepth = 0;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '.' && bracketDepth == 0) {
                appendSegment(result, segment.toString());
                segment.setLength(0);
                continue;
            }
            if (ch == '[') {
                bracketDepth++;
            } else if (ch == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
            }
            segment.append(ch);
        }
        appendSegment(result, segment.toString());
        return result.toString();
    }

    private void appendSegment(StringBuilder result, String rawSegment) {
        if (rawSegment == null || rawSegment.isBlank()) {
            return;
        }
        String segment = rawSegment.trim();
        if (segment.startsWith("[")) {
            result.append(segment);
            return;
        }
        int bracketIndex = segment.indexOf('[');
        String fieldName = bracketIndex >= 0 ? segment.substring(0, bracketIndex) : segment;
        String suffix = bracketIndex >= 0 ? segment.substring(bracketIndex) : "";
        result.append("['").append(escapeFieldName(fieldName)).append("']").append(suffix);
    }

    private String escapeFieldName(String fieldName) {
        return fieldName.replace("\\", "\\\\").replace("'", "\\'");
    }
}
