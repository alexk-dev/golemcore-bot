package me.golemcore.bot.cli;

import java.util.List;

import me.golemcore.bot.cli.adapter.in.picocli.CliRootCommand;
import me.golemcore.bot.cli.router.CliCommandCatalog;
import picocli.CommandLine;

public final class CliCommandFactory {

    private CliCommandFactory() {
    }

    public static CommandLine create() {
        return create(new CliRootCommand());
    }

    public static CommandLine create(CliRootCommand rootCommand) {
        return CliApplication.commandLine(rootCommand);
    }

    public static List<String> subcommandNames() {
        return CliCommandCatalog.subcommandNames();
    }
}
