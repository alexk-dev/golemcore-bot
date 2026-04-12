package me.golemcore.bot.port.outbound;

/**
 * Domain-facing access to static memory settings.
 */
public interface MemorySettingsPort {

    MemorySettings memory();

    record MemorySettings(String directory) {
    }
}
