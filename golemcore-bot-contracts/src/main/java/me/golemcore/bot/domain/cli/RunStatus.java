package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stable run lifecycle status exposed in final results and APIs.
 */
public enum RunStatus {
    STARTED("started"), RUNNING("running"), COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled");

    private final String serializedValue;

    RunStatus(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RunStatus fromWireValue(String value) {
        for (RunStatus runStatus : values()) {
            if (runStatus.serializedValue.equals(value)) {
                return runStatus;
            }
        }
        throw new IllegalArgumentException("Unsupported run status: " + value);
    }
}
