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
    private static final String INTERNAL_FILE_URL_KEY = "internal_file_url";
    private static final String INTERNAL_FILE_NAME_KEY = "internal_file_name";
    private static final String INTERNAL_FILE_MIME_TYPE_KEY = "internal_file_mime_type";
    private static final String INTERNAL_FILE_KIND_KEY = "internal_file_kind";
    private static final String INTERNAL_FILE_THUMBNAIL_BASE64_KEY = "internal_file_thumbnail_base64";
    private static final String ATTACHMENTS_METADATA_KEY = "attachments";

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
        Map<String, Object> metadata = buildToolMetadata(context, outcome);
        Message toolMsg = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("tool")
                .toolCallId(outcome.toolCallId())
                .toolName(outcome.toolName())
                .content(outcome.messageContent())
                .metadata(metadata)
                .timestamp(now())
                .build();

        recordTurnOutputAttachments(context, metadata);
        context.getMessages().add(toolMsg);
        if (context.getSession() != null) {
            context.getSession().addMessage(toolMsg);
        }
    }

    @Override
    public void appendFinalAssistantAnswer(AgentContext context, LlmResponse llmResponse, String finalText) {
        Map<String, Object> metadata = buildAssistantMetadata(context, llmResponse);
        List<Map<String, Object>> attachments = context.getAttribute(ContextAttributes.TURN_OUTPUT_ATTACHMENTS);
        if (attachments != null && !attachments.isEmpty()) {
            metadata.put(ATTACHMENTS_METADATA_KEY, copyAttachments(attachments));
        }
        Message assistant = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(finalText)
                .toolCalls(llmResponse != null ? llmResponse.getToolCalls() : null)
                .metadata(metadata)
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

        if (context.getActiveSkill() != null && context.getActiveSkill().getName() != null
                && !context.getActiveSkill().getName().isBlank()) {
            metadata.put(ContextAttributes.ACTIVE_SKILL_NAME, context.getActiveSkill().getName());
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
                || path.isBlank()) {
            return metadata;
        }

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", kind.toLowerCase(java.util.Locale.ROOT));
        attachment.put("internalFilePath", path);

        Object urlValue = data.get(INTERNAL_FILE_URL_KEY);
        if (urlValue instanceof String url && !url.isBlank()) {
            attachment.put("url", url);
        }

        Object thumbnailValue = data.get(INTERNAL_FILE_THUMBNAIL_BASE64_KEY);
        if (thumbnailValue instanceof String thumbnailBase64 && !thumbnailBase64.isBlank()) {
            attachment.put("thumbnailBase64", thumbnailBase64);
        }

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

    @SuppressWarnings("unchecked")
    private void recordTurnOutputAttachments(AgentContext context, Map<String, Object> metadata) {
        if (context == null || metadata == null) {
            return;
        }
        Object attachmentsRaw = metadata.get(TOOL_ATTACHMENTS_METADATA_KEY);
        if (!(attachmentsRaw instanceof List<?> attachments) || attachments.isEmpty()) {
            return;
        }

        List<Map<String, Object>> current = context.getAttribute(ContextAttributes.TURN_OUTPUT_ATTACHMENTS);
        List<Map<String, Object>> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
        for (Object attachmentObj : attachments) {
            if (attachmentObj instanceof Map<?, ?> attachmentMap) {
                next.add(new LinkedHashMap<>((Map<String, Object>) attachmentMap));
            }
        }
        context.setAttribute(ContextAttributes.TURN_OUTPUT_ATTACHMENTS, next);
    }

    private List<Map<String, Object>> copyAttachments(List<Map<String, Object>> attachments) {
        List<Map<String, Object>> copied = new ArrayList<>(attachments.size());
        for (Map<String, Object> attachment : attachments) {
            copied.add(new LinkedHashMap<>(attachment));
        }
        return copied;
    }
}
