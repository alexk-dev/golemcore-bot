package me.golemcore.bot.cli;

import picocli.CommandLine.ITypeConverter;

enum CliAttachMode {
    AUTO("auto"), REQUIRED("required"), NEVER("never");

    private final String cliValue;

    CliAttachMode(String cliValue) {
        this.cliValue = cliValue;
    }

    static CliAttachMode parse(String value) {
        for (CliAttachMode mode : values()) {
            if (mode.cliValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported attach mode: " + value);
    }

    String cliValue() {
        return cliValue;
    }

    static final class Converter implements ITypeConverter<CliAttachMode> {

        @Override
        public CliAttachMode convert(String value) {
            return parse(value);
        }
    }
}
