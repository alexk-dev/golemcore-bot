package me.golemcore.bot.port.outbound;

/**
 * Port for recording anonymous telemetry rollup counters.
 */
public interface TelemetryRollupPort {

    void recordModelUsage(String modelId, String tier, int inputTokens, int outputTokens, int totalTokens);

    void recordPluginInstall(String pluginId);

    void recordPluginUninstall(String pluginId);

    void recordPluginAction(String routeKey, String actionId);

    void recordPluginSettingsSave(String routeKey);
}
