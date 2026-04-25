package me.golemcore.bot.domain.model;

/**
 * Whether a tool execution failure can be automatically recovered within the
 * current tool loop turn.
 */
public enum ToolFailureRecoverability {

    /** Immediate stop is the safest behavior. */
    FATAL,

    /** Retrying the same high-level action may succeed later. */
    RETRYABLE,

    /** The model should first adjust command/path/workdir before retrying. */
    SELF_CORRECTABLE,

    /** Recovery requires explicit user action or a different permission path. */
    USER_ACTION_REQUIRED
}
