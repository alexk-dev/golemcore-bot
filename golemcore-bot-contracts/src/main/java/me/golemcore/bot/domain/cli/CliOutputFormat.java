package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Output format requested by a CLI client.
 */
public enum CliOutputFormat {
    TEXT("text"), JSON("json"), NDJSON("ndjson"), MARKDOWN("markdown");

    private final String serializedValue;

    CliOutputFormat(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CliOutputFormat fromWireValue(String value) {
        for (CliOutputFormat outputFormat : values()) {
            if (outputFormat.serializedValue.equals(value)) {
                return outputFormat;
            }
        }
        throw new IllegalArgumentException("Unsupported CLI output format: " + value);
    }
}
