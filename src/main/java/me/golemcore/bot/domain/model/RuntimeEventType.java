package me.golemcore.bot.domain.model;

/**
 * Stable runtime event types emitted by the agent loop and tool loop.
 */
public enum RuntimeEventType {
    TURN_STARTED, TURN_INTERRUPT_REQUESTED, TURN_FINISHED, TURN_FAILED, LLM_STARTED, LLM_FINISHED, TOOL_STARTED, TOOL_FINISHED, RETRY_STARTED, RETRY_FINISHED, COMPACTION_STARTED, COMPACTION_FINISHED
}
