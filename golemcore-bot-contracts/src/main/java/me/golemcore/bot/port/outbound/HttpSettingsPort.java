package me.golemcore.bot.port.outbound;

/**
 * Domain-facing access to static HTTP client settings.
 */
public interface HttpSettingsPort {

    HttpSettings http();

    record HttpSettings(long connectTimeoutMillis) {
    }
}
