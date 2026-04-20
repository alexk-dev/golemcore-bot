package me.golemcore.bot.port.outbound;

import java.time.Duration;

/**
 * Runtime properties for extension/plugin artifact discovery and marketplace
 * access.
 */
public interface PluginInstallationsPort {

    boolean isPluginRuntimeEnabled();

    boolean isPluginAutoStartEnabled();

    boolean isPluginAutoReloadEnabled();

    String getPluginDirectory();

    Duration getPluginPollInterval();

    boolean isPluginMarketplaceEnabled();

    String getPluginMarketplaceRepositoryDirectory();

    String getPluginMarketplaceRepositoryUrl();

    String getPluginMarketplaceBranch();

    String getPluginMarketplaceApiBaseUrl();

    String getPluginMarketplaceRawBaseUrl();

    Duration getPluginMarketplaceRemoteCacheTtl();
}
