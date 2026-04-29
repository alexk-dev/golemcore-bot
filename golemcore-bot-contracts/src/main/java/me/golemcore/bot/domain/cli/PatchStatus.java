package me.golemcore.bot.domain.cli;

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

    public String wireValue() {
        return serializedValue;
    }
}
