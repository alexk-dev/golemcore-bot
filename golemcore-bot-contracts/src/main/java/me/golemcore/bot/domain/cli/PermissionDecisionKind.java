package me.golemcore.bot.domain.cli;

/**
 * User or policy decision for a pending tool permission request.
 */
public enum PermissionDecisionKind {
    ALLOW_ONCE("allow_once"), ALLOW_SESSION("allow_session"), ALLOW_PROJECT("allow_project"), DENY(
            "deny"), DENY_SESSION("deny_session"), EDIT("edit"), EXPLAIN("explain");

    private final String wireValue;

    PermissionDecisionKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
