package me.golemcore.bot.domain.context;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Resolves the rendered system-prompt token budget for context assembly.
 */
public interface SystemPromptBudgetPolicy {

    int resolveSystemPromptThreshold(AgentContext context);
}
