package me.golemcore.bot.port.outbound;

/**
 * Domain-facing access to workspace path settings.
 */
public interface WorkspaceSettingsPort {

    WorkspaceSettings workspace();

    record WorkspaceSettings(String filesystemWorkspace, String shellWorkspace) {
    }
}
