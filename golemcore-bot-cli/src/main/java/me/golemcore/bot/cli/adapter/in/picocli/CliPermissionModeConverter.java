package me.golemcore.bot.cli.adapter.in.picocli;

import me.golemcore.bot.domain.cli.CliPermissionMode;
import picocli.CommandLine.ITypeConverter;

public final class CliPermissionModeConverter implements ITypeConverter<CliPermissionMode> {

    @Override
    public CliPermissionMode convert(String value) {
        for (CliPermissionMode mode : CliPermissionMode.values()) {
            if (mode.wireValue().equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported permission mode: " + value);
    }
}
