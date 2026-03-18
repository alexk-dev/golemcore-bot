package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.AutoRunContextSupport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default implementation that appends messages to both context.messages and
 * session.messages.
 */
public class DefaultHistoryWriter implements HistoryWriter {

    private static final String DEFAULT_MODEL_TIER = "balanced";
    private static final String TOOL_ATTACHMENTS_METADATA_KEY = "toolAttachments";
    private static final String INTERNAL_FILE_PATH_KEY = "internal_file_path";
    private static final String INTERNAL_FILE_NAME_KEY = "internal_file_name";
    private static final String INTERNAL_FILE_MIME_TYPE_KEY = "internal_file_mime_type";
    private static final String INTERNAL_FILE_KIND_KEY = "internal_file_kind";

    private final Clock clock;

    public DefaultHistoryWriter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void appendAssistantToolCalls(AgentContext context, LlmResponse llmResponse,
            List<Message.ToolCall> toolCalls) {
        Message assistant = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(llmResponse != null ? llmResponse.getContent() : null)
                .toolCalls(toolCalls)
                .metadata(buildAssistantMetadata(context, llmResponse))
                .timestamp(now())
                .build();

        context.getMessages().add(assistant);
        if (context.getSession() != null) {
            context.getSession().addMessage(assistant);
        }
    }

    @Override
    public void appendToolResult(AgentContext context, ToolExecutionOutcome outcome) {
        Message toolMsg = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("tool")
                .toolCallId(outcome.toolCallId())
                .toolName(outcome.toolName())
                .content(outcome.messageContent())
                .metadata(buildToolMetadata(context, outcome))
                .timestamp(now())
                .build();

        context.getMessages().add(toolMsg);
        if (context.getSession() != null) {
            context.getSession().addMessage(toolMsg);
        }
    }

    @Override
    public void appendFinalAssistantAnswer(AgentContext context, LlmResponse llmResponse, String finalText) {
        Message assistant = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(finalText)
                .toolCalls(llmResponse != null ? llmResponse.getToolCalls() : null)
                .metadata(buildAssistantMetadata(context, llmResponse))
                .timestamp(now())
                .build();

        context.getMessages().add(assistant);
        if (context.getSession() != null) {
            context.getSession().addMessage(assistant);
        }
    }

    private Instant now() {
        return clock != null ? clock.instant() : Instant.now();
    }

    private Map<String, Object> buildAssistantMetadata(AgentContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context == null) {
            return metadata;
        }

        String tier = context.getModelTier();
        metadata.put("modelTier", tier != null && !tier.isBlank() ? tier : DEFAULT_MODEL_TIER);

        String model = null;
        if (context.getSession() != null && context.getSession().getMetadata() != null) {
            Object value = context.getSession().getMetadata().get(ContextAttributes.LLM_MODEL);
            if (value instanceof String) {
                model = (String) value;
            }
        }
        if (model != null && !model.isBlank()) {
            metadata.put("model", model);
        }

        String reasoning = context.getAttribute(ContextAttributes.LLM_REASONING);
        if (reasoning != null && !reasoning.isBlank() && !"none".equals(reasoning)) {
            metadata.put("reasoning", reasoning);
        }

        metadata.putAll(AutoRunContextSupport.buildAutoMessageMetadata(context));
        return metadata;
    }

    private Map<String, Object> buildAssistantMetadata(AgentContext context, LlmResponse llmResponse) {
        Map<String, Object> metadata = buildAssistantMetadata(context);
        if (llmResponse != null && llmResponse.getProviderMetadata() != null) {
            metadata.putAll(llmResponse.getProviderMetadata());
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildToolMetadata(AgentContext context, ToolExecutionOutcome outcome) {
        Map<String, Object> metadata = buildAssistantMetadata(context);
        if (outcome == null || outcome.toolResult() == null
                || !(outcome.toolResult().getData() instanceof Map<?, ?> rawMap)) {
            return metadata;
        }

        Map<String, Object> data = (Map<String, Object>) rawMap;
        Object kindValue = data.get(INTERNAL_FILE_KIND_KEY);
        Object pathValue = data.get(INTERNAL_FILE_PATH_KEY);
        if (!(kindValue instanceof String kind)
                || !(pathValue instanceof String path)
                || !"image".equalsIgnoreCase(kind)
                || path.isBlank()) {
            return metadata;
        }

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "image");
        attachment.put("internalFilePath", path);

        Object filenameValue = data.get(INTERNAL_FILE_NAME_KEY);
        if (filenameValue instanceof String filename && !filename.isBlank()) {
            attachment.put("name", filename);
        }

        Object mimeTypeValue = data.get(INTERNAL_FILE_MIME_TYPE_KEY);
        if (mimeTypeValue instanceof String mimeType && !mimeType.isBlank()) {
            attachment.put("mimeType", mimeType);
        }

        List<Map<String, Object>> attachments = new ArrayList<>();
        attachments.add(attachment);
        metadata.put(TOOL_ATTACHMENTS_METADATA_KEY, attachments);
        return metadata;
    }
}
