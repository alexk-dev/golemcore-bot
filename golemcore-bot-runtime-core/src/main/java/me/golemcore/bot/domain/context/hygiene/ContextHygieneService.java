package me.golemcore.bot.domain.context.hygiene;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributeSpec;

import java.util.Map;

/**
 * Cleanup hooks for transient context artifacts.
 */
public interface ContextHygieneService {

    void afterSystem(AgentContext context, String systemName);

    void afterOuterIteration(AgentContext context);

    void beforePersist(AgentContext context);

    Map<String, ContextAttributeSpec> specs();
}
