package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.RuntimeConfig;

public final class HiveRuntimeConfigSupport {

    private static final boolean DEFAULT_HIVE_SDLC_FUNCTION_ENABLED = true;

    private HiveRuntimeConfigSupport() {
    }

    public static RuntimeConfig.HiveConfig getHiveConfig(RuntimeConfig runtimeConfig) {
        if (runtimeConfig == null || runtimeConfig.getHive() == null) {
            return RuntimeConfig.HiveConfig.builder().build();
        }
        return runtimeConfig.getHive();
    }

    public static boolean isHiveEnabled(RuntimeConfig runtimeConfig) {
        Boolean enabled = getHiveConfig(runtimeConfig).getEnabled();
        return enabled != null && enabled;
    }

    public static boolean isHiveSdlcCurrentContextEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig, getHiveSdlcConfig(runtimeConfig).getCurrentContextEnabled());
    }

    public static boolean isHiveSdlcCardReadEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig, getHiveSdlcConfig(runtimeConfig).getCardReadEnabled());
    }

    public static boolean isHiveSdlcCardSearchEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig, getHiveSdlcConfig(runtimeConfig).getCardSearchEnabled());
    }

    public static boolean isHiveSdlcThreadMessageEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig, getHiveSdlcConfig(runtimeConfig).getThreadMessageEnabled());
    }

    public static boolean isHiveSdlcReviewRequestEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig, getHiveSdlcConfig(runtimeConfig).getReviewRequestEnabled());
    }

    public static boolean isHiveSdlcFollowupCardCreateEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig,
                getHiveSdlcConfig(runtimeConfig).getFollowupCardCreateEnabled());
    }

    public static boolean isHiveSdlcLifecycleSignalEnabled(RuntimeConfig runtimeConfig) {
        return isHiveSdlcFunctionEnabled(runtimeConfig, getHiveSdlcConfig(runtimeConfig).getLifecycleSignalEnabled());
    }

    private static RuntimeConfig.HiveSdlcConfig getHiveSdlcConfig(RuntimeConfig runtimeConfig) {
        RuntimeConfig.HiveConfig hiveConfig = getHiveConfig(runtimeConfig);
        if (hiveConfig.getSdlc() == null) {
            return RuntimeConfig.HiveSdlcConfig.builder().build();
        }
        return hiveConfig.getSdlc();
    }

    private static boolean isHiveSdlcFunctionEnabled(RuntimeConfig runtimeConfig, Boolean value) {
        return isHiveEnabled(runtimeConfig) && (value != null ? value : DEFAULT_HIVE_SDLC_FUNCTION_ENABLED);
    }
}
