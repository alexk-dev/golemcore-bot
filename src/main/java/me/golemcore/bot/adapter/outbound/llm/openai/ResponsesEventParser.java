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

package me.golemcore.bot.adapter.outbound.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.LlmChunk;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.LlmUsage;
import me.golemcore.bot.domain.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses server-sent events from the OpenAI {@code /v1/responses} streaming
 * endpoint into domain {@link LlmChunk} objects, and parses synchronous
 * (non-streaming) JSON responses into {@link LlmResponse} objects.
 *
 * <p>
 * SSE event types handled:
 * <ul>
 * <li>{@code response.output_text.delta} — text content delta
 * <li>{@code response.function_call_arguments.delta} — tool call arguments
 * delta
 * <li>{@code response.output_item.done} — completed output item (message or
 * function_call)
 * <li>{@code response.completed} — response finished, includes usage
 * <li>{@code response.failed} — response failed with error
 * <li>{@code response.incomplete} — response truncated
 * </ul>
 */
public class ResponsesEventParser {

    private static final Logger log = LoggerFactory.getLogger(ResponsesEventParser.class);

    private final ObjectMapper objectMapper;

    public ResponsesEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a single SSE event into an {@link LlmChunk}, or {@code null} if
     * the event type does not produce user-visible output.
     *
     * @param eventType the SSE event type (after {@code event: })
     * @param data      the JSON data payload (after {@code data: })
     * @return a chunk, or {@code null} for events that should be silently skipped
     */
    public LlmChunk parseStreamEvent(String eventType, String data) {
        try {
            JsonNode node = objectMapper.readTree(data);

            return switch (eventType) {
            case "response.output_text.delta" -> {
                String delta = textField(node, "delta");
                yield LlmChunk.builder().text(delta).done(false).build();
            }
            case "response.function_call_arguments.delta" -> {
                // Accumulate argument deltas — the full function_call is emitted at
                // response.output_item.done, but we can signal partial progress here.
                yield null;
            }
            case "response.output_item.done" -> parseOutputItemDone(node);
            case "response.completed" -> parseCompleted(node);
            case "response.failed" -> {
                String errorMsg = extractErrorMessage(node);
                log.error("[OpenAI Responses] Stream failed: {}", errorMsg);
                throw new RuntimeException("OpenAI Responses API stream failed: " + errorMsg);
            }
            case "response.incomplete" -> {
                log.warn("[OpenAI Responses] Response incomplete (truncated)");
                yield LlmChunk.builder().done(true).finishReason("length").build();
            }
            default -> null; // Silently skip unknown event types
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OpenAI Responses] Failed to parse SSE event '{}': {}", eventType, e.getMessage());
            return null;
        }
    }

    /**
     * Parse a complete (non-streaming) JSON response body into an
     * {@link LlmResponse}.
     */
    public LlmResponse parseSyncResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return buildLlmResponse(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI Responses API response: " + e.getMessage(), e);
        }
    }

    private LlmResponse buildLlmResponse(JsonNode root) {
        StringBuilder textContent = new StringBuilder();
        List<Message.ToolCall> toolCalls = new ArrayList<>();
        String model = textField(root, "model");

        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                String type = textField(item, "type");
                if ("message".equals(type)) {
                    extractMessageContent(item, textContent);
                } else if ("function_call".equals(type)) {
                    Message.ToolCall tc = extractToolCall(item);
                    if (tc != null) {
                        toolCalls.add(tc);
                    }
                }
            }
        }

        LlmUsage usage = extractUsage(root.path("usage"));
        String finishReason = mapStatus(textField(root, "status"));

        return LlmResponse.builder()
                .content(textContent.toString())
                .toolCalls(toolCalls)
                .usage(usage)
                .model(model)
                .finishReason(finishReason)
                .build();
    }

    private LlmChunk parseOutputItemDone(JsonNode node) {
        JsonNode item = node.path("item");
        String type = textField(item, "type");

        if ("function_call".equals(type)) {
            Message.ToolCall tc = extractToolCall(item);
            if (tc != null) {
                return LlmChunk.builder().toolCall(tc).done(false).build();
            }
        }
        // message items are delivered via text deltas; output_item.done for
        // messages is informational — no extra chunk needed.
        return null;
    }

    private LlmChunk parseCompleted(JsonNode node) {
        JsonNode response = node.has("response") ? node.path("response") : node;
        LlmUsage usage = extractUsage(response.path("usage"));
        return LlmChunk.builder().done(true).usage(usage).finishReason("stop").build();
    }

    private void extractMessageContent(JsonNode item, StringBuilder textContent) {
        JsonNode content = item.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("output_text".equals(textField(part, "type"))) {
                    String text = textField(part, "text");
                    if (text != null) {
                        textContent.append(text);
                    }
                }
            }
        }
    }

    private Message.ToolCall extractToolCall(JsonNode item) {
        String id = textField(item, "call_id");
        String name = textField(item, "name");
        String arguments = textField(item, "arguments");
        if (name == null) {
            return null;
        }

        Map<String, Object> args = parseArguments(arguments);
        return Message.ToolCall.builder()
                .id(id)
                .name(name)
                .arguments(args)
                .build();
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            JsonNode node = objectMapper.readTree(arguments);
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJavaValue(entry.getValue())));
            return map;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // If arguments are malformed, return them as a raw string entry
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_raw", arguments);
            return fallback;
        }
    }

    private Object toJavaValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNull()) {
            return null;
        }
        // Arrays and objects — return as string to keep ToolCall.arguments
        // Map<String,Object> simple
        return node.toString();
    }

    private LlmUsage extractUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode()) {
            return null;
        }
        int inputTokens = usageNode.path("input_tokens").asInt(0);
        int outputTokens = usageNode.path("output_tokens").asInt(0);
        return LlmUsage.of(inputTokens, outputTokens);
    }

    private String mapStatus(String status) {
        if (status == null) {
            return "stop";
        }
        return switch (status) {
        case "completed" -> "stop";
        case "incomplete" -> "length";
        case "failed" -> "error";
        default -> status;
        };
    }

    private String extractErrorMessage(JsonNode node) {
        JsonNode error = node.path("error");
        if (!error.isMissingNode()) {
            String msg = textField(error, "message");
            if (msg != null) {
                return msg;
            }
        }
        // Fallback: try response-level error
        JsonNode response = node.path("response");
        if (!response.isMissingNode()) {
            JsonNode respError = response.path("error");
            if (!respError.isMissingNode()) {
                return textField(respError, "message");
            }
        }
        return "Unknown error";
    }

    private String textField(JsonNode node, String fieldName) {
        JsonNode valueNode = node.path(fieldName);
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        return valueNode.asText();
    }
}
