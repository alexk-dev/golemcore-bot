package me.golemcore.bot.domain.cli;

/**
 * Stable status for tool execution records.
 */
public enum ToolExecutionStatus {
    REQUESTED("requested"), STARTED("started"), COMPLETED("completed"), FAILED("failed"), CANCELLED(
            "cancelled"), DENIED("denied");

    private final String wireValue;

    ToolExecutionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
