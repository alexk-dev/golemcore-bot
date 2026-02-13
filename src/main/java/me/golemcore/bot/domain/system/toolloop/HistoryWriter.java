package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;

import java.util.List;

/**
 * Single point of mutation for raw history (AgentSession.messages) during a
 * turn.
 *
 * <p>
 * ToolLoopSystem should not write messages directly.
 */
public interface HistoryWriter {

    void appendAssistantToolCalls(AgentContext context, LlmResponse llmResponse, List<Message.ToolCall> toolCalls);

    void appendToolResult(AgentContext context, ToolExecutionOutcome outcome);

    void appendFinalAssistantAnswer(AgentContext context, LlmResponse llmResponse, String finalText);
}
