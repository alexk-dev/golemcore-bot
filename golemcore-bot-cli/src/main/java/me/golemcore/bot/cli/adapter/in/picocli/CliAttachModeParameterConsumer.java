package me.golemcore.bot.cli.adapter.in.picocli;

import java.util.Stack;
import me.golemcore.bot.cli.config.CliAttachMode;
import picocli.CommandLine.IParameterConsumer;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;

public final class CliAttachModeParameterConsumer implements IParameterConsumer {

    @Override
    public void consumeParameters(Stack<String> args, ArgSpec argSpec, CommandSpec commandSpec) {
        if (!args.isEmpty() && isAttachMode(args.peek())) {
            argSpec.setValue(CliAttachMode.parse(args.pop()));
            return;
        }
        argSpec.setValue(CliAttachMode.REQUIRED);
    }

    private static boolean isAttachMode(String value) {
        for (CliAttachMode mode : CliAttachMode.values()) {
            if (mode.cliValue().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
