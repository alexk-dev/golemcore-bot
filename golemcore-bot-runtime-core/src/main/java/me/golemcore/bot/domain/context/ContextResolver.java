package me.golemcore.bot.domain.context;

import me.golemcore.bot.domain.model.AgentContext;

/**
 * Resolves cross-cutting context state before layers are assembled.
 */
public interface ContextResolver {

    void resolve(AgentContext context);
}
