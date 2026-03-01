package me.golemcore.bot.domain.model;

/**
 * Trigger source for conversation compaction.
 */
public enum CompactionReason {
    AUTO_THRESHOLD, MANUAL_COMMAND, CONTEXT_OVERFLOW_RECOVERY
}
