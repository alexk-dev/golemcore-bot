package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_AUTO_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigSupport.normalizeUtcTimeValue;

import me.golemcore.bot.domain.model.RuntimeConfig;

public interface UpdateRuntimeConfigView extends RuntimeConfigSource {
    default boolean isAutoUpdateEnabled() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_AUTO_ENABLED;
        }
        Boolean val = updateConfig.getAutoEnabled();
        return val != null ? val : DEFAULT_UPDATE_AUTO_ENABLED;
    }

    default int getUpdateCheckIntervalMinutes() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES;
        }
        Integer val = updateConfig.getCheckIntervalMinutes();
        return val != null ? val : DEFAULT_UPDATE_CHECK_INTERVAL_MINUTES;
    }

    default boolean isUpdateMaintenanceWindowEnabled() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED;
        }
        Boolean val = updateConfig.getMaintenanceWindowEnabled();
        return val != null ? val : DEFAULT_UPDATE_MAINTENANCE_WINDOW_ENABLED;
    }

    default String getUpdateMaintenanceWindowStartUtc() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC;
        }
        return normalizeUtcTimeValue(updateConfig.getMaintenanceWindowStartUtc(),
                DEFAULT_UPDATE_MAINTENANCE_WINDOW_START_UTC);
    }

    default String getUpdateMaintenanceWindowEndUtc() {
        RuntimeConfig.UpdateConfig updateConfig = getRuntimeConfig().getUpdate();
        if (updateConfig == null) {
            return DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC;
        }
        return normalizeUtcTimeValue(updateConfig.getMaintenanceWindowEndUtc(),
                DEFAULT_UPDATE_MAINTENANCE_WINDOW_END_UTC);
    }
}
