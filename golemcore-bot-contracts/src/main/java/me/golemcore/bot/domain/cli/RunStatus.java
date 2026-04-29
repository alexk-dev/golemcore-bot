package me.golemcore.bot.domain.cli;

/**
 * Stable run lifecycle status exposed in final results and APIs.
 */
public enum RunStatus {
    STARTED("started"), RUNNING("running"), COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled");

    private final String serializedValue;

    RunStatus(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String wireValue() {
        return serializedValue;
    }
}
