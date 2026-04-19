package me.golemcore.bot.domain.model;

/**
 * Machine-readable classification of tool execution failures.
 *
 * <p>
 * This exists to avoid relying on string matching in tool error messages.
 */
public enum ToolFailureKind {

    /**
     * Tool execution was blocked because user confirmation was required but denied.
     */
    CONFIRMATION_DENIED,

    /**
     * Tool execution was denied by policy (e.g. tool unknown, disabled, skill
     * unavailable).
     */
    POLICY_DENIED,

    /**
     * Tool execution failed during runtime (exceptions, timeouts, non-zero exit,
     * etc.).
     */
    EXECUTION_FAILED
}
