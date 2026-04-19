package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Resolves request-time context budgets for a target model.
 */
public interface ContextBudgetResolver {

    ContextBudget resolve(AgentContext context, String targetModel);
}
