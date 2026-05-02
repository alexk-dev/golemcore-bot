package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Permission policy requested for CLI tool execution.
 */
public enum CliPermissionMode {
    ASK("ask"), READ_ONLY("read-only"), PLAN("plan"), EDIT("edit"), FULL("full");

    private final String serializedValue;

    CliPermissionMode(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CliPermissionMode fromWireValue(String value) {
        for (CliPermissionMode permissionMode : values()) {
            if (permissionMode.serializedValue.equals(value)) {
                return permissionMode;
            }
        }
        throw new IllegalArgumentException("Unsupported CLI permission mode: " + value);
    }
}
