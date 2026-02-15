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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms arbitrary JSON payloads into message strings using templates
 * with {@code {field.path}} placeholders.
 *
 * <p>Example template: {@code "Push to {repository.name} by {pusher.name}"}
 * applied to a GitHub push event extracts nested JSON values via dot-notation.
 */
@Component
@Slf4j
public class WebhookPayloadTransformer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Applies the template to the raw JSON payload, resolving all
     * {@code {field.path}} placeholders.
     *
     * @param template message template with placeholders
     * @param rawBody  raw JSON bytes from the incoming request
     * @return resolved message string, with unresolved placeholders replaced by
     *         {@code <missing>}
     */
    public String transform(String template, byte[] rawBody) {
        if (template == null || template.isBlank()) {
            return new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("[Webhook] Failed to parse JSON payload for template resolution: {}", e.getMessage());
            return template;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            String value = resolveJsonPath(root, path);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveJsonPath(JsonNode root, String path) {
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (current == null || current.isMissingNode()) {
                return "<missing>";
            }
            current = current.get(segment);
        }
        if (current == null || current.isMissingNode()) {
            return "<missing>";
        }
        return current.isTextual() ? current.asText() : current.toString();
    }
}
