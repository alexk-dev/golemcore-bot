package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;

/** Minimal result for TDD/BDD. Will be extended as implementation evolves. */
public record ToolLoopTurnResult(AgentContext context,boolean finalAnswerReady,int llmCalls,int toolExecutions){}
