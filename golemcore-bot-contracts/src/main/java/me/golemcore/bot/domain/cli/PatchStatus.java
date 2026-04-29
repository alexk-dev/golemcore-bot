package me.golemcore.bot.domain.cli;

/**
 * Patch lifecycle state visible to CLI, TUI, WebUI, and API clients.
 */
public enum PatchStatus {
    PROPOSED("proposed"), APPLIED("applied"), ACCEPTED("accepted"), REJECTED("rejected"), CONFLICTED(
            "conflicted"), REVERTED("reverted");

    private final String wireValue;

    PatchStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
