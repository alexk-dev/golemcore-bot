package me.golemcore.bot.port.outbound;

public interface HiveRuntimeMetadataPort {

    String runtimeVersion();

    String buildVersion();

    String defaultHostLabel();

    long uptimeSeconds();
}
