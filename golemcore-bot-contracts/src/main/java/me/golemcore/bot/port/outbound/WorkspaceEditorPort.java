package me.golemcore.bot.port.outbound;

/**
 * High-level workspace editing contract used by reusable client-facing flows.
 */
public interface WorkspaceEditorPort {

    void validateEditablePath(String relativePath);
}
