package me.golemcore.bot.domain.system.toolloop.view;

/**
 * Coarse item categories used by request-time context hygiene.
 */
public enum ContextItemKind {
    USER, ASSISTANT, TOOL_INTERACTION, TOOL_RESULT, SYSTEM, OTHER
}
