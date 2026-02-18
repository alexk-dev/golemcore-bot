package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default implementation that appends messages to both context.messages and
 * session.messages.
 */
public class DefaultHistoryWriter implements HistoryWriter {

    private final Clock clock;

    public DefaultHistoryWriter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void appendAssistantToolCalls(AgentContext context, LlmResponse llmResponse,
            java.util.List<Message.ToolCall> toolCalls) {
        Message assistant = Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(llmResponse != null ? llmResponse.getContent() : null)
                .toolCalls(toolCalls)
                .metadata(buildAssistantMetadata(context))
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
                .metadata(buildAssistantMetadata(context))
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
        if (tier != null && !tier.isBlank()) {
            metadata.put("modelTier", tier);
        }

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

        return metadata;
    }
}
