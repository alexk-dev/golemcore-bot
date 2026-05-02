package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Patch lifecycle state visible to CLI, TUI, WebUI, and API clients.
 */
public enum PatchStatus {
    PROPOSED("proposed"), APPLIED("applied"), ACCEPTED("accepted"), REJECTED("rejected"), CONFLICTED(
            "conflicted"), REVERTED("reverted");

    private final String serializedValue;

    PatchStatus(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String wireValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PatchStatus fromWireValue(String value) {
        for (PatchStatus patchStatus : values()) {
            if (patchStatus.serializedValue.equals(value)) {
                return patchStatus;
            }
        }
        throw new IllegalArgumentException("Unsupported patch status: " + value);
    }
}
