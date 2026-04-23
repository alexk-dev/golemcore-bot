package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Builds a budgeted request-time message projection from raw conversation
 * history.
 */
public interface ContextWindowProjector {

    ConversationView project(AgentContext context, ConversationView rawView, ContextBudget budget);
}
