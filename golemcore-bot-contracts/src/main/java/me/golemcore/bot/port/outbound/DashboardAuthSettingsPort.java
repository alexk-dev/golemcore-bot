package me.golemcore.bot.port.outbound;

public interface DashboardAuthSettingsPort {

    boolean isDashboardEnabled();

    String getConfiguredAdminPassword();
}
