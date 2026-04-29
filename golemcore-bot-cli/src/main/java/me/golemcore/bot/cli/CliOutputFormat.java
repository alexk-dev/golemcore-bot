package me.golemcore.bot.cli;

import picocli.CommandLine.ITypeConverter;

enum CliOutputFormat {
    TEXT("text"), JSON("json"), NDJSON("ndjson"), MARKDOWN("markdown");

    private final String serializedValue;

    CliOutputFormat(String serializedValue) {
        this.serializedValue = serializedValue;
    }

    static CliOutputFormat parse(String value) {
        for (CliOutputFormat format : values()) {
            if (format.serializedValue.equals(value)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported output format: " + value);
    }

    String cliValue() {
        return serializedValue;
    }

    static final class Converter implements ITypeConverter<CliOutputFormat> {

        @Override
        public CliOutputFormat convert(String value) {
            return parse(value);
        }
    }
}
