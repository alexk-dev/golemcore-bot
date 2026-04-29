package me.golemcore.bot.cli.config;

public enum CliAttachMode {
    AUTO("auto"), REQUIRED("required"), NEVER("never");

    private final String serializedValue;

    CliAttachMode(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    public static CliAttachMode parse(String value) {
        for (CliAttachMode mode : values()) {
            if (mode.serializedValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported attach mode: " + value);
    }

    public String cliValue() {
        return serializedValue;
    }

}
