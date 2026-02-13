package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Executes LLM -> tools -> LLM loop inside a single agent turn.
 *
 * <p>
 * This is intentionally not an {@code AgentSystem} yet; it's a domain-level
 * orchestrator that can be invoked from the existing pipeline.
 */
public interface ToolLoopSystem {

    ToolLoopTurnResult processTurn(AgentContext context);
}
