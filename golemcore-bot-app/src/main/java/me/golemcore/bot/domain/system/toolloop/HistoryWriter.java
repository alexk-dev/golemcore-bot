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
 * The tool loop system should not write messages directly — all history
 * mutations go through this interface. This ensures a consistent message
 * structure and allows downstream systems to rely on message metadata
 * conventions.
 *
 * @see DefaultHistoryWriter
 */
public interface HistoryWriter {

    /**
     * Appends an assistant message with tool calls to conversation history.
     *
     * @param context
     *            the agent context
     * @param llmResponse
     *            the LLM response (for metadata extraction)
     * @param toolCalls
     *            the tool calls to record
     */
    void appendAssistantToolCalls(AgentContext context, LlmResponse llmResponse, List<Message.ToolCall> toolCalls);

    /**
     * Appends a tool result message to conversation history.
     *
     * @param context
     *            the agent context
     * @param outcome
     *            the tool execution outcome
     */
    void appendToolResult(AgentContext context, ToolExecutionOutcome outcome);

    /**
     * Appends the final assistant answer to conversation history.
     *
     * @param context
     *            the agent context
     * @param llmResponse
     *            the LLM response (for metadata extraction)
     * @param finalText
     *            the final answer text
     */
    void appendFinalAssistantAnswer(AgentContext context, LlmResponse llmResponse, String finalText);

    /**
     * Appends an internal recovery hint as a {@code user} message.
     *
     * <p>
     * The hint is marked with internal metadata so downstream systems can
     * distinguish it from real user input. Uses {@code role("user")} to preserve
     * LLM turn alternation.
     *
     * @param context
     *            the agent context
     * @param hint
     *            the recovery guidance text
     */
    void appendInternalRecoveryHint(AgentContext context, String hint);
}
