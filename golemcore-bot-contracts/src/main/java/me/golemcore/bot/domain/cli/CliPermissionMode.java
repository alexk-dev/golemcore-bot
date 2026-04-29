package me.golemcore.bot.domain.cli;

/**
 * Permission policy requested for CLI tool execution.
 */
public enum CliPermissionMode {
    ASK("ask"), READ_ONLY("read-only"), PLAN("plan"), EDIT("edit"), FULL("full");

    private final String serializedValue;

    CliPermissionMode(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String wireValue() {
        return serializedValue;
    }
}
