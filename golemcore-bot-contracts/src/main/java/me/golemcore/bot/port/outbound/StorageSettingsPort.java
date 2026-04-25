package me.golemcore.bot.port.outbound;

/**
 * Domain-facing access to storage path settings.
 */
public interface StorageSettingsPort {

    StorageSettings storage();

    record StorageSettings(String basePath) {
    }
}
