package me.golemcore.bot.domain.cli;

/**
 * Risk level assigned to a tool invocation before policy evaluation.
 */
public enum PermissionRisk {
    LOW("low"), MEDIUM("medium"), HIGH("high"), CRITICAL("critical");

    private final String serializedValue;

    PermissionRisk(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String wireValue() {
        return serializedValue;
    }
}
