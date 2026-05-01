package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Workspace trust state used before allowing project-local tools.
 */
public enum ProjectTrustState {
    TRUSTED("trusted"), UNTRUSTED("untrusted"), RESTRICTED("restricted"), NEVER_TRUST("never-trust");

    private final String serializedValue;

    ProjectTrustState(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ProjectTrustState fromWireValue(String value) {
        for (ProjectTrustState trustState : values()) {
            if (trustState.serializedValue.equals(value)) {
                return trustState;
            }
        }
        throw new IllegalArgumentException("Unsupported project trust state: " + value);
    }
}
