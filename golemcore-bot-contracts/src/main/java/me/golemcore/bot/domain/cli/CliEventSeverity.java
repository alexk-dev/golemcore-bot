package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Severity level for CLI event streams.
 */
public enum CliEventSeverity {
    DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error");

    private final String serializedValue;

    CliEventSeverity(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CliEventSeverity fromWireValue(String value) {
        for (CliEventSeverity severity : values()) {
            if (severity.serializedValue.equals(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unsupported CLI event severity: " + value);
    }
}
