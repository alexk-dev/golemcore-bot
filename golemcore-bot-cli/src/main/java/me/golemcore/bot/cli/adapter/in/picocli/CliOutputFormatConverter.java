package me.golemcore.bot.cli.adapter.in.picocli;

import me.golemcore.bot.domain.cli.CliOutputFormat;
import picocli.CommandLine.ITypeConverter;

public final class CliOutputFormatConverter implements ITypeConverter<CliOutputFormat> {

    @Override
    public CliOutputFormat convert(String value) {
        for (CliOutputFormat format : CliOutputFormat.values()) {
            if (format.wireValue().equals(value)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported output format: " + value);
    }
}
