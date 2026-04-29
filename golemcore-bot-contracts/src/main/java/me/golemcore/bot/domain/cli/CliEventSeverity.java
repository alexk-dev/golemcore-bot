package me.golemcore.bot.domain.cli;

/**
 * Severity level for CLI event streams.
 */
public enum CliEventSeverity {
    DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");

    private final String wireValue;

    CliEventSeverity(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
