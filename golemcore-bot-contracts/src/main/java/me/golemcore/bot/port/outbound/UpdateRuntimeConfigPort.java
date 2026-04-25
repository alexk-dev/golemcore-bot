package me.golemcore.bot.port.outbound;

import me.golemcore.bot.domain.model.RuntimeConfig;

/**
 * Domain-facing access to mutable update-related runtime configuration.
 */
public interface UpdateRuntimeConfigPort {

    RuntimeConfig.UpdateConfig getUpdateConfig();

    RuntimeConfig.UpdateConfig updateUpdateConfig(RuntimeConfig.UpdateConfig updateConfig);

    boolean isAutoUpdateEnabled();

    Integer getUpdateCheckIntervalMinutes();

    boolean isUpdateMaintenanceWindowEnabled();

    String getUpdateMaintenanceWindowStartUtc();

    String getUpdateMaintenanceWindowEndUtc();
}
