package me.golemcore.bot.cli;

import java.io.PrintWriter;

import picocli.CommandLine;

/**
 * Programmatic entrypoint for the terminal CLI adapter.
 */
public final class CliApplication {

    private CliApplication() {
    }

    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        CommandLine commandLine = commandLine(new CliRootCommand(out, err));
        return commandLine.execute(args);
    }

    static CommandLine commandLine(CliRootCommand rootCommand) {
        CommandLine commandLine = new CommandLine(rootCommand);
        commandLine.setOut(rootCommand.out());
        commandLine.setErr(rootCommand.err());
        commandLine.setUnmatchedArgumentsAllowed(false);
        return commandLine;
    }
}
