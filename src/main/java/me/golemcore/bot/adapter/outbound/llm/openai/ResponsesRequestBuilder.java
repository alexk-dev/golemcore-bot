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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Builds JSON request bodies for the OpenAI {@code /v1/responses} endpoint.
 *
 * <p>
 * Maps domain {@link LlmRequest} objects to the Responses API wire format,
 * handling role translation ({@code system} → {@code developer}), tool
 * definitions, reasoning effort, and conversation history including tool call
 * results.
 */
public class ResponsesRequestBuilder {

    private final ObjectMapper objectMapper;

    public ResponsesRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Build the full JSON body for a {@code POST /v1/responses} call.
     *
     * @param request
     *            the domain LLM request
     * @param stream
     *            whether to request server-sent events
     * @return JSON object ready for serialization
     */
    public ObjectNode buildRequestBody(LlmRequest request, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("model", stripProviderPrefix(request.getModel()));

        ArrayNode input = buildInput(request);
        body.set("input", input);

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            body.set("tools", buildTools(request.getTools()));
        }

        if (request.getReasoningEffort() != null && !request.getReasoningEffort().isBlank()) {
            ObjectNode reasoning = objectMapper.createObjectNode();
            reasoning.put("effort", request.getReasoningEffort());
            body.set("reasoning", reasoning);
        }

        if (request.getMaxTokens() != null) {
            body.put("max_output_tokens", request.getMaxTokens());
        }

        if (request.getTemperature() > 0) {
            body.put("temperature", request.getTemperature());
        }

        body.put("stream", stream);

        return body;
    }

    private ArrayNode buildInput(LlmRequest request) {
        ArrayNode input = objectMapper.createArrayNode();

        // System prompt becomes "developer" role in Responses API
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "developer");
            systemMsg.put("content", request.getSystemPrompt());
            input.add(systemMsg);
        }

        if (request.getMessages() == null) {
            return input;
        }

        for (Message msg : request.getMessages()) {
            String mappedRole = mapRole(msg.getRole());
            if (mappedRole != null) {
                input.add(buildSimpleMessage(mappedRole, msg.getContent()));
                continue;
            }
            switch (msg.getRole()) {
            case "assistant" -> {
                if (msg.hasToolCalls()) {
                    // Emit an assistant text message if content is present
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        ObjectNode textNode = objectMapper.createObjectNode();
                        textNode.put("role", "assistant");
                        textNode.put("content", msg.getContent());
                        input.add(textNode);
                    }
                    // Emit each tool call as a function_call output item
                    for (Message.ToolCall tc : msg.getToolCalls()) {
                        ObjectNode callNode = objectMapper.createObjectNode();
                        callNode.put("type", "function_call");
                        callNode.put("id", tc.getId() != null ? tc.getId() : "");
                        callNode.put("call_id", tc.getId() != null ? tc.getId() : "");
                        callNode.put("name", tc.getName());
                        callNode.put("arguments", serializeArguments(tc.getArguments()));
                        input.add(callNode);
                    }
                } else {
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("role", "assistant");
                    node.put("content", nonNull(msg.getContent()));
                    input.add(node);
                }
            }
            case "tool" -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("type", "function_call_output");
                node.put("call_id", msg.getToolCallId() != null ? msg.getToolCallId() : "");
                node.put("output", nonNull(msg.getContent()));
                input.add(node);
            }
            default -> {
                /* handled by mapRole above */ }
            }
        }

        return input;
    }

    private ArrayNode buildTools(List<ToolDefinition> tools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");

            ObjectNode fnNode = objectMapper.createObjectNode();
            fnNode.put("name", tool.getName());
            if (tool.getDescription() != null) {
                fnNode.put("description", tool.getDescription());
            }
            if (tool.getInputSchema() != null && !tool.getInputSchema().isEmpty()) {
                fnNode.set("parameters", objectMapper.valueToTree(tool.getInputSchema()));
            }
            toolNode.set("function", fnNode);
            toolsArray.add(toolNode);
        }
        return toolsArray;
    }

    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private String stripProviderPrefix(String model) {
        if (model == null) {
            return "";
        }
        return model.contains("/") ? model.substring(model.indexOf('/') + 1) : model;
    }

    /**
     * Map a domain message role to a Responses API role, or return {@code null} for
     * roles that need special handling (assistant, tool).
     */
    private String mapRole(String role) {
        if ("system".equals(role)) {
            return "developer";
        }
        if ("assistant".equals(role) || "tool".equals(role)) {
            return null;
        }
        // user and any unknown role → user
        return "user";
    }

    private ObjectNode buildSimpleMessage(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", nonNull(content));
        return node;
    }

    private String nonNull(String text) {
        return text != null ? text : "";
    }
}
