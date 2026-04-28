package me.golemcore.bot.domain.runtimeconfig;

public interface AutoModeConfigView {
    boolean isAutoModeEnabled();

    boolean isAutoStartEnabled();

    boolean isAutoReflectionEnabled();

    boolean isAutoNotifyMilestonesEnabled();
}
