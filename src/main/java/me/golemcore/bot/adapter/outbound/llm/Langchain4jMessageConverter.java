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

package me.golemcore.bot.adapter.outbound.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.port.outbound.ToolArtifactReadPort;

@RequiredArgsConstructor
@Slf4j
class Langchain4jMessageConverter {

    private static final String GEMINI_THINKING_SIGNATURE_KEY = "thinking_signature";
    private static final String TOOL_ATTACHMENTS_METADATA_KEY = "toolAttachments";
    private static final String SYNTH_ID_PREFIX = "synth_call_";
    private static final String TOOL_ATTACHMENT_REOPEN_HINT = "Re-open the file with a tool if deeper inspection is needed.";

    private final ToolArtifactReadPort toolArtifactReadPort;
    private final ObjectMapper objectMapper;

    MessageConversionResult convertMessages(String systemPrompt, List<Message> requestMessages,
            boolean geminiApiType, boolean visionCapableTarget, boolean disableToolAttachmentHydration) {
        List<ChatMessage> messages = new ArrayList<>();
        List<Message> normalizedMessages = normalizeMessagesForProvider(requestMessages, geminiApiType);
        boolean hydratedToolImages = false;

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }

        if (normalizedMessages == null) {
            return new MessageConversionResult(messages, false);
        }

        int synthCounter = 0;
        Deque<String> pendingSynthIds = new ArrayDeque<>();
        Set<String> emittedToolCallIds = new HashSet<>();
        int firstTrailingToolIndex = findFirstTrailingToolMessageIndex(normalizedMessages);

        for (int index = 0; index < normalizedMessages.size(); index++) {
            Message msg = normalizedMessages.get(index);
            switch (msg.getRole()) {
            case "user" -> messages.add(toUserMessage(msg));
            case "assistant" -> {
                if (msg.hasToolCalls()) {
                    List<ToolExecutionRequest> toolRequests = new ArrayList<>();
                    for (Message.ToolCall tc : msg.getToolCalls()) {
                        String id = tc.getId();
                        if (id == null || id.isBlank()) {
                            synthCounter++;
                            id = SYNTH_ID_PREFIX + synthCounter;
                            pendingSynthIds.addLast(id);
                        }
                        emittedToolCallIds.add(id);
                        toolRequests.add(ToolExecutionRequest.builder()
                                .id(id)
                                .name(tc.getName())
                                .arguments(Langchain4jToolArgumentJson.toJson(tc.getArguments(), objectMapper))
                                .build());
                    }
                    AiMessage.Builder aiMessageBuilder = AiMessage.builder()
                            .toolExecutionRequests(toolRequests);
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        aiMessageBuilder.text(msg.getContent());
                    }
                    String thinkingSignature = geminiApiType ? extractGeminiThinkingSignature(msg) : null;
                    if (thinkingSignature != null) {
                        aiMessageBuilder.attributes(Map.of(GEMINI_THINKING_SIGNATURE_KEY, thinkingSignature));
                    }
                    messages.add(aiMessageBuilder.build());
                } else {
                    messages.add(AiMessage.from(nonNullText(msg.getContent())));
                }
            }
            case "tool" -> {
                String toolCallId = msg.getToolCallId();
                if (toolCallId == null || toolCallId.isBlank()) {
                    if (pendingSynthIds.isEmpty()) {
                        synthCounter++;
                        toolCallId = SYNTH_ID_PREFIX + synthCounter;
                    } else {
                        toolCallId = pendingSynthIds.pollFirst();
                    }
                }
                boolean hydrateToolImages = shouldHydrateToolAttachments(index, firstTrailingToolIndex,
                        visionCapableTarget, disableToolAttachmentHydration);
                if (emittedToolCallIds.contains(toolCallId)) {
                    messages.add(ToolExecutionResultMessage.from(
                            toolCallId,
                            msg.getToolName(),
                            nonNullText(msg.getContent())));
                    UserMessage toolVisualContext = toToolAttachmentContextMessage(msg, hydrateToolImages);
                    if (toolVisualContext != null) {
                        messages.add(toolVisualContext);
                        hydratedToolImages = hydratedToolImages || hasImageContent(toolVisualContext);
                    }
                } else {
                    log.debug("[LLM] Converting orphaned tool message to text: tool={}", msg.getToolName());
                    String toolText = "[Tool: " + nonNullText(msg.getToolName())
                            + "]\n[Result: " + nonNullText(msg.getContent()) + "]";
                    UserMessage orphanedToolContext = toToolAttachmentContextMessage(msg, hydrateToolImages);
                    messages.add(orphanedToolContext != null ? orphanedToolContext : UserMessage.from(toolText));
                    if (orphanedToolContext != null) {
                        hydratedToolImages = hydratedToolImages || hasImageContent(orphanedToolContext);
                    }
                }
            }
            case "system" -> messages.add(SystemMessage.from(nonNullText(msg.getContent())));
            default -> log.warn("Unknown message role: {}, treating as user message", msg.getRole());
            }
        }

        return new MessageConversionResult(messages, hydratedToolImages);
    }

    static boolean isUnsupportedFunctionRoleError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                if (message.contains("unsupported_value")
                        && message.contains("does not support 'function'")) {
                    return true;
                }
                if (message.contains("role 'tool' must be a response to")
                        && message.contains("tool_calls")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private List<Message> normalizeMessagesForProvider(List<Message> requestMessages, boolean geminiApiType) {
        if (!geminiApiType || requestMessages == null || requestMessages.isEmpty()) {
            return requestMessages;
        }

        long missingSignatureCount = requestMessages.stream()
                .filter(Message::isAssistantMessage)
                .filter(Message::hasToolCalls)
                .filter(msg -> extractGeminiThinkingSignature(msg) == null)
                .count();
        if (missingSignatureCount == 0) {
            return requestMessages;
        }

        log.warn(
                "[LLM] Flattening tool history for Gemini request because {} assistant tool-call message(s) are missing thinking_signature",
                missingSignatureCount);
        return Message.flattenToolMessages(requestMessages);
    }

    private String extractGeminiThinkingSignature(Message msg) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }
        Object value = msg.getMetadata().get(GEMINI_THINKING_SIGNATURE_KEY);
        if (value instanceof String signature && !signature.isBlank()) {
            return signature;
        }
        return null;
    }

    private String nonNullText(String text) {
        return text != null ? text : "";
    }

    private int findFirstTrailingToolMessageIndex(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1;
        }

        int index = messages.size() - 1;
        while (index >= 0) {
            Message message = messages.get(index);
            if (message == null || !"tool".equals(message.getRole())) {
                break;
            }
            index--;
        }

        int firstTrailingToolIndex = index + 1;
        return firstTrailingToolIndex < messages.size() ? firstTrailingToolIndex : -1;
    }

    private boolean shouldHydrateToolAttachments(int messageIndex, int firstTrailingToolIndex,
            boolean visionCapableTarget, boolean disableToolAttachmentHydration) {
        return visionCapableTarget
                && !disableToolAttachmentHydration
                && firstTrailingToolIndex >= 0
                && messageIndex >= firstTrailingToolIndex;
    }

    @SuppressWarnings("unchecked")
    private UserMessage toUserMessage(Message msg) {
        List<Content> contents = new ArrayList<>();

        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            contents.add(TextContent.from(msg.getContent()));
        }

        Map<String, Object> metadata = msg.getMetadata();
        if (metadata != null) {
            Object attachmentsRaw = metadata.get("attachments");
            if (attachmentsRaw instanceof List<?> attachments) {
                for (Object attachmentObj : attachments) {
                    if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                        continue;
                    }

                    Object typeObj = attachmentMap.get("type");
                    Object mimeObj = attachmentMap.get("mimeType");
                    Object dataObj = attachmentMap.get("dataBase64");

                    if (!(typeObj instanceof String type)
                            || !(mimeObj instanceof String mimeType)
                            || !(dataObj instanceof String base64Data)) {
                        continue;
                    }
                    if (!"image".equals(type) || !mimeType.startsWith("image/") || base64Data.isBlank()) {
                        continue;
                    }

                    Image image = Image.builder()
                            .base64Data(base64Data)
                            .mimeType(mimeType)
                            .build();
                    contents.add(ImageContent.from(image));
                }
            }
        }

        if (contents.isEmpty()) {
            return UserMessage.from(msg.getContent() != null ? msg.getContent() : "");
        }

        return UserMessage.from(contents);
    }

    @SuppressWarnings("unchecked")
    private UserMessage toToolAttachmentContextMessage(Message msg, boolean hydrateImages) {
        if (msg == null || msg.getMetadata() == null) {
            return null;
        }

        Object attachmentsRaw = msg.getMetadata().get(TOOL_ATTACHMENTS_METADATA_KEY);
        if (!(attachmentsRaw instanceof List<?> attachments)) {
            return null;
        }

        List<Content> contents = new ArrayList<>();
        String text = buildToolAttachmentContextText(msg, attachments);
        if (text != null && !text.isBlank()) {
            contents.add(TextContent.from(text));
        }

        if (!hydrateImages || toolArtifactReadPort == null) {
            return contents.isEmpty() ? null : UserMessage.from(contents);
        }

        for (Object attachmentObj : attachments) {
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }

            Object typeObj = attachmentMap.get("type");
            Object pathObj = attachmentMap.get("internalFilePath");
            if (!(typeObj instanceof String type)
                    || !(pathObj instanceof String internalFilePath)
                    || !"image".equals(type)
                    || internalFilePath.isBlank()) {
                continue;
            }

            try {
                ToolArtifactDownload download = toolArtifactReadPort.getDownload(internalFilePath);
                String mimeType = download.getMimeType();
                if (mimeType == null || !mimeType.startsWith("image/")) {
                    continue;
                }
                String base64Data = Base64.getEncoder().encodeToString(download.getData());
                Image image = Image.builder()
                        .base64Data(base64Data)
                        .mimeType(mimeType)
                        .build();
                contents.add(ImageContent.from(image));
            } catch (RuntimeException ex) {
                log.warn("[LLM] Failed to hydrate tool image attachment '{}': {}", internalFilePath, ex.getMessage());
            }
        }

        if (contents.isEmpty()) {
            return null;
        }

        return UserMessage.from(contents);
    }

    private String buildToolAttachmentContextText(Message msg, List<?> attachments) {
        String toolName = msg.getToolName() != null && !msg.getToolName().isBlank()
                ? msg.getToolName()
                : "tool";
        List<String> lines = new ArrayList<>();
        lines.add("Tool artifact from " + toolName + " is available.");

        boolean foundAttachment = false;
        for (Object attachmentObj : attachments) {
            if (!(attachmentObj instanceof Map<?, ?> attachmentMap)) {
                continue;
            }
            String path = objectAsString(attachmentMap.get("internalFilePath"));
            if (path == null || path.isBlank()) {
                continue;
            }
            String name = objectAsString(attachmentMap.get("name"));
            String mimeType = objectAsString(attachmentMap.get("mimeType"));
            String displayName = name != null && !name.isBlank() ? name : path.substring(path.lastIndexOf('/') + 1);
            StringBuilder line = new StringBuilder("- ").append(displayName);
            if (mimeType != null && !mimeType.isBlank()) {
                line.append(" (").append(mimeType).append(")");
            }
            line.append(" @ ").append(path);
            lines.add(line.toString());
            foundAttachment = true;
        }

        if (!foundAttachment) {
            return null;
        }

        lines.add(TOOL_ATTACHMENT_REOPEN_HINT);
        return String.join("\n", lines);
    }

    private String objectAsString(Object value) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }

    private boolean hasImageContent(UserMessage message) {
        if (message == null || message.contents() == null) {
            return false;
        }
        for (Content content : message.contents()) {
            if (content.type() == dev.langchain4j.data.message.ContentType.IMAGE) {
                return true;
            }
        }
        return false;
    }
}
