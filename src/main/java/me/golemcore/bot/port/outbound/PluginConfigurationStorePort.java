package me.golemcore.bot.port.outbound;

import java.util.Map;

/**
 * Persists plugin-owned configuration outside the runtime config aggregate.
 */
public interface PluginConfigurationStorePort {

    boolean hasConfig(String pluginId);

    Map<String, Object> loadConfig(String pluginId);

    void saveConfig(String pluginId, Map<String, Object> config);

    void deleteConfig(String pluginId);
}
