package me.golemcore.bot.domain.system.toolloop.repeat;

/**
 * State domain observed or invalidated by a tool call.
 */
public enum ToolStateDomain {
    WORKSPACE, MEMORY, AUTONOMY_PROGRESS, HIVE_REMOTE, SCHEDULING, UNKNOWN
}
