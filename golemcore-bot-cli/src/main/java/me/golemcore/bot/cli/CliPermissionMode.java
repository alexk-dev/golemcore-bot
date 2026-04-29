package me.golemcore.bot.cli;

import picocli.CommandLine.ITypeConverter;

enum CliPermissionMode {
    ASK("ask"), READ_ONLY("read-only"), PLAN("plan"), EDIT("edit"), FULL("full");

    private final String cliValue;

    CliPermissionMode(String cliValue) {
        this.cliValue = cliValue;
    }

    static CliPermissionMode parse(String value) {
        for (CliPermissionMode mode : values()) {
            if (mode.cliValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported permission mode: " + value);
    }

    String cliValue() {
        return cliValue;
    }

    static final class Converter implements ITypeConverter<CliPermissionMode> {

        @Override
        public CliPermissionMode convert(String value) {
            return parse(value);
        }
    }
}
