package me.golemcore.bot.domain.system.toolloop.repeat;

/**
 * Coarse semantic class for a tool call repeat policy.
 */
public enum ToolUseCategory {
    OBSERVE, POLL, MUTATE_IDEMPOTENT, MUTATE_NON_IDEMPOTENT, EXECUTE_UNKNOWN, CONTROL
}
