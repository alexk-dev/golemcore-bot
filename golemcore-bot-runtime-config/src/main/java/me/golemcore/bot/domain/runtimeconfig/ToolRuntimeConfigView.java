package me.golemcore.bot.domain.runtimeconfig;

public interface ToolRuntimeConfigView {
    boolean isFilesystemEnabled();

    boolean isShellEnabled();

    boolean isToolConfirmationEnabled();

    int getToolConfirmationTimeoutSeconds();
}
