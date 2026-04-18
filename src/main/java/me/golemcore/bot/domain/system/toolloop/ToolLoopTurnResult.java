package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Minimal result for TDD/BDD. Will be extended as implementation evolves.
 *
 * @param context
 *            final agent context after the turn
 * @param finalAnswerReady
 *            whether a final answer is ready to deliver
 * @param llmCalls
 *            number of LLM calls performed during the turn
 * @param toolExecutions
 *            number of tool executions performed during the turn
 */
public record ToolLoopTurnResult(AgentContext context,boolean finalAnswerReady,int llmCalls,int toolExecutions){}
