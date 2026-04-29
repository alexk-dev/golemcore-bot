package me.golemcore.bot.domain.cli;

/**
 * Severity level for CLI event streams.
 */
public enum CliEventSeverity {
    DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");

    private final String serializedValue;

    CliEventSeverity(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String wireValue() {
        return serializedValue;
    }
}
