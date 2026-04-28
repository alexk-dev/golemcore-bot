package me.golemcore.bot.domain.runtimeconfig;

import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_MODEL_TIER;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_REFLECTION_ENABLED;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_TICK_INTERVAL_SECONDS;
import static me.golemcore.bot.domain.runtimeconfig.RuntimeConfigDefaults.DEFAULT_AUTO_TIMEOUT_MINUTES;

public interface AutoModeConfigView extends RuntimeConfigSource {
    default boolean isAutoModeEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getEnabled();
        return val != null ? val : true;
    }

    default int getAutoTickIntervalSeconds() {
        Integer val = getRuntimeConfig().getAutoMode().getTickIntervalSeconds();
        return val != null ? val : DEFAULT_AUTO_TICK_INTERVAL_SECONDS;
    }

    default int getAutoTaskTimeLimitMinutes() {
        Integer val = getRuntimeConfig().getAutoMode().getTaskTimeLimitMinutes();
        return val != null ? val : DEFAULT_AUTO_TIMEOUT_MINUTES;
    }

    default boolean isAutoStartEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getAutoStart();
        return val != null ? val : true;
    }

    default String getAutoModelTier() {
        String val = getRuntimeConfig().getAutoMode().getModelTier();
        return val != null ? val : DEFAULT_AUTO_MODEL_TIER;
    }

    default boolean isAutoReflectionEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getReflectionEnabled();
        return val != null ? val : DEFAULT_AUTO_REFLECTION_ENABLED;
    }

    default int getAutoReflectionFailureThreshold() {
        Integer val = getRuntimeConfig().getAutoMode().getReflectionFailureThreshold();
        return val != null ? val : DEFAULT_AUTO_REFLECTION_FAILURE_THRESHOLD;
    }

    default String getAutoReflectionModelTier() {
        return getRuntimeConfig().getAutoMode().getReflectionModelTier();
    }

    default boolean isAutoReflectionTierPriority() {
        Boolean val = getRuntimeConfig().getAutoMode().getReflectionTierPriority();
        return val != null && val;
    }

    default boolean isAutoNotifyMilestonesEnabled() {
        Boolean val = getRuntimeConfig().getAutoMode().getNotifyMilestones();
        return val != null ? val : true;
    }
}
