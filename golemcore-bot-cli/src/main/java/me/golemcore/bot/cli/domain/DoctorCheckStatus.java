package me.golemcore.bot.cli.domain;

public enum DoctorCheckStatus {
    OK("ok"), WARN("warn"), ERROR("error");

    private final String serializedValue;

    DoctorCheckStatus(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public String serializedValue() {
        return serializedValue;
    }
}
