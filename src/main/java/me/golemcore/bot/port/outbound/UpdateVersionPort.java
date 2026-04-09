package me.golemcore.bot.port.outbound;

/**
 * Exposes the runtime build version to update workflows.
 */
public interface UpdateVersionPort {

    String currentVersion();
}
