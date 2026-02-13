package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Builds a request-time conversation view for a specific target model.
 */
public interface ConversationViewBuilder {

    ConversationView buildView(AgentContext context, String targetModel);
}
