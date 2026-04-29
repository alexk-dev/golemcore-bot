package me.golemcore.bot.domain.cli;

/**
 * Risk level assigned to a tool invocation before policy evaluation.
 */
public enum PermissionRisk {
    LOW("low"), MEDIUM("medium"), HIGH("high"), CRITICAL("critical");

    private final String wireValue;

    PermissionRisk(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
