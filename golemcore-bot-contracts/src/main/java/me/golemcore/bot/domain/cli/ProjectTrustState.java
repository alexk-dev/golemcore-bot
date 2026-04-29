package me.golemcore.bot.domain.cli;

/**
 * Workspace trust state used before allowing project-local tools.
 */
public enum ProjectTrustState {
    TRUSTED("trusted"), UNTRUSTED("untrusted"), RESTRICTED("restricted"), NEVER_TRUST("never-trust");

    private final String wireValue;

    ProjectTrustState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
