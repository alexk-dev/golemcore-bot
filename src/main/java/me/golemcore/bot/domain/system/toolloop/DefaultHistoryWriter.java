package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;

import java.time.Clock;
import java.time.Instant;
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
}
