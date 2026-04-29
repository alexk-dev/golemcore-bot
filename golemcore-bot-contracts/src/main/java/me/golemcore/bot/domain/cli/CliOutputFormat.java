package me.golemcore.bot.domain.cli;

/**
 * Output format requested by a CLI client.
 */
public enum CliOutputFormat {
    TEXT("text"), JSON("json"), NDJSON("ndjson"), MARKDOWN("markdown");

    private final String wireValue;

    CliOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
