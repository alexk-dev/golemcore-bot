package me.golemcore.bot.domain.model;

/**
 * Hygiene metadata for one {@link AgentContext} attribute key.
 *
 * @param key
 *            attribute key from {@link ContextAttributes}
 * @param scope
 *            retention scope for cleanup hooks
 * @param promptVisible
 *            whether the value may be rendered into LLM prompts
 * @param traceVisible
 *            whether the value may be copied to traces/runtime events
 */
public record ContextAttributeSpec(String key,ContextScope scope,boolean promptVisible,boolean traceVisible){}
