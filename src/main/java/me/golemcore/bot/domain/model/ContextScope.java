package me.golemcore.bot.domain.model;

/**
 * Retention scope for transient {@link AgentContext} attributes.
 */
public enum ContextScope {
    ITERATION, TURN, SESSION, PERSISTENT
}
