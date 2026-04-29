package me.golemcore.bot.domain.cli;

/**
 * Permission policy requested for CLI tool execution.
 */
public enum CliPermissionMode {
    ASK("ask"), READ_ONLY("read-only"), PLAN("plan"), EDIT("edit"), FULL("full");

    private final String wireValue;

    CliPermissionMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
