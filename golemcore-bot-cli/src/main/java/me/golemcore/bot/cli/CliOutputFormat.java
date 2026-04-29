package me.golemcore.bot.cli;

import picocli.CommandLine.ITypeConverter;

enum CliOutputFormat {
    TEXT("text"), JSON("json"), NDJSON("ndjson"), MARKDOWN("markdown");

    private final String cliValue;

    CliOutputFormat(String cliValue) {
        this.cliValue = cliValue;
    }

    static CliOutputFormat parse(String value) {
        for (CliOutputFormat format : values()) {
            if (format.cliValue.equals(value)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported output format: " + value);
    }

    String cliValue() {
        return cliValue;
    }

    static final class Converter implements ITypeConverter<CliOutputFormat> {

        @Override
        public CliOutputFormat convert(String value) {
            return parse(value);
        }
    }
}
