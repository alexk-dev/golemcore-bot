package me.golemcore.bot.cli.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DoctorCheckStatus {
    OK("ok"), WARN("warn"), ERROR("error");

    private final String serializedValue;

    DoctorCheckStatus(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    @JsonValue
    public String serializedValue() {
        return serializedValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DoctorCheckStatus fromSerializedValue(String value) {
        for (DoctorCheckStatus status : values()) {
            if (status.serializedValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported doctor check status: " + value);
    }
}
