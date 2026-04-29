package me.golemcore.bot.cli.adapter.in.picocli;

import me.golemcore.bot.cli.config.CliAttachMode;
import picocli.CommandLine.ITypeConverter;

public final class CliAttachModeConverter implements ITypeConverter<CliAttachMode> {

    @Override
    public CliAttachMode convert(String value) {
        return CliAttachMode.parse(value);
    }
}
