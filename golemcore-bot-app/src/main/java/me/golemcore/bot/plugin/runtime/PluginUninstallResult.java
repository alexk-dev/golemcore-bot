package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned after a plugin uninstall attempt.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginUninstallResult {

    private String status;
    private String message;
}
