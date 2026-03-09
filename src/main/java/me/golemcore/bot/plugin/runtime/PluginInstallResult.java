package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned after a plugin installation attempt.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginInstallResult {

    private String status;
    private String message;
    private PluginMarketplaceItem plugin;
}
