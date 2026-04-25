package me.golemcore.bot.plugin.runtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Install request for marketplace-driven plugin installation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginInstallRequest {

    private String pluginId;
    private String version;
}
