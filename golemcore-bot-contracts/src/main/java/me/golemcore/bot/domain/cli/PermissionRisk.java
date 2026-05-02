package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Risk level assigned to a tool invocation before policy evaluation.
 */
public enum PermissionRisk {
    LOW("low"), MEDIUM("medium"), HIGH("high"), CRITICAL("critical");

    private final String serializedValue;

    PermissionRisk(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PermissionRisk fromWireValue(String value) {
        for (PermissionRisk risk : values()) {
            if (risk.serializedValue.equals(value)) {
                return risk;
            }
        }
        throw new IllegalArgumentException("Unsupported permission risk: " + value);
    }
}
