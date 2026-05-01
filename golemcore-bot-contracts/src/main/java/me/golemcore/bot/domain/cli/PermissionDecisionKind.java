package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * User or policy decision for a pending tool permission request.
 */
public enum PermissionDecisionKind {
    ALLOW_ONCE("allow_once"), ALLOW_SESSION("allow_session"), ALLOW_PROJECT("allow_project"), DENY(
            "deny"), DENY_SESSION("deny_session"), EDIT("edit"), EXPLAIN("explain");

    private final String serializedValue;

    PermissionDecisionKind(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PermissionDecisionKind fromWireValue(String value) {
        for (PermissionDecisionKind decisionKind : values()) {
            if (decisionKind.serializedValue.equals(value)) {
                return decisionKind;
            }
        }
        throw new IllegalArgumentException("Unsupported permission decision kind: " + value);
    }
}
