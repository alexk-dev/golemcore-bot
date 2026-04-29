package me.golemcore.bot.cli;

import picocli.CommandLine.ITypeConverter;

enum CliAttachMode {
    AUTO("auto"), REQUIRED("required"), NEVER("never");

    private final String serializedValue;

    CliAttachMode(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    static CliAttachMode parse(String value) {
        for (CliAttachMode mode : values()) {
            if (mode.serializedValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported attach mode: " + value);
    }

    String cliValue() {
        return serializedValue;
    }

    static final class Converter implements ITypeConverter<CliAttachMode> {

        @Override
        public CliAttachMode convert(String value) {
            return parse(value);
        }
    }
}
