package me.golemcore.bot.domain.cli;

/**
 * Output format requested by a CLI client.
 */
public enum CliOutputFormat {
    TEXT("text"), JSON("json"), NDJSON("ndjson"), MARKDOWN("markdown");

    private final String serializedValue;

    CliOutputFormat(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String wireValue() {
        return serializedValue;
    }
}
