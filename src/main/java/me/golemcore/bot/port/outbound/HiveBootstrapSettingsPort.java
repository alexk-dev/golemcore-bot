package me.golemcore.bot.port.outbound;

public interface HiveBootstrapSettingsPort {

    Boolean enabled();

    Boolean autoConnectOnStartup();

    String joinCode();

    String displayName();

    String hostLabel();
}
