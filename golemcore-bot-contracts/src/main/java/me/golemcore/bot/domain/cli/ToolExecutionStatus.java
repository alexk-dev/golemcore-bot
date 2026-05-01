package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable status for tool execution records.
 */
public enum ToolExecutionStatus {
    REQUESTED("requested"), STARTED("started"), COMPLETED("completed"), FAILED("failed"), CANCELLED(
            "cancelled"), DENIED("denied");

    private final String serializedValue;

    ToolExecutionStatus(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ToolExecutionStatus fromWireValue(String value) {
        for (ToolExecutionStatus executionStatus : values()) {
            if (executionStatus.serializedValue.equals(value)) {
                return executionStatus;
            }
        }
        throw new IllegalArgumentException("Unsupported tool execution status: " + value);
    }
}
