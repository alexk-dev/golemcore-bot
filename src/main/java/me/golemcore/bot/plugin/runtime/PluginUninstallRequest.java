package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Uninstall request for marketplace-managed plugin artifacts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginUninstallRequest {

    private String pluginId;
}
