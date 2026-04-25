package me.golemcore.bot.port.outbound;

/**
 * Domain-facing localization and language state contract.
 */
public interface LocalizationPort {

    String defaultLanguage();

    void setLanguage(String language);

    String getMessage(String key, String language, Object... args);
}
