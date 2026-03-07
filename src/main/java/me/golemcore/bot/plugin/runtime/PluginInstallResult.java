package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result returned after a plugin installation attempt.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginInstallResult {

    private String status;
    private String message;
    private PluginMarketplaceItem plugin;
}
