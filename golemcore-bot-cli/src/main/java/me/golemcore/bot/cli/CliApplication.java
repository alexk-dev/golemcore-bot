package me.golemcore.bot.cli;

import java.io.PrintWriter;

import me.golemcore.bot.cli.adapter.in.picocli.CliRootCommand;
import me.golemcore.bot.cli.router.CliCommandCatalog;
import me.golemcore.bot.cli.router.CliCommandDescriptor;
import picocli.CommandLine;
import picocli.CommandLine.HelpCommand;

/**
 * Programmatic entrypoint for the terminal CLI adapter.
 */
public final class CliApplication {

    private CliApplication() {
    }

    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        return run(args, out, err, CliDependencies.defaults());
    }

    public static int run(String[] args, PrintWriter out, PrintWriter err, CliDependencies dependencies) {
        CommandLine commandLine = commandLine(new CliRootCommand(out, err, dependencies));
        return commandLine.execute(args);
    }

    static CommandLine commandLine(CliRootCommand rootCommand) {
        CommandLine commandLine = new CommandLine(rootCommand);
        CliCommandCatalog.descriptors().forEach(descriptor -> addSubcommand(commandLine, descriptor));
        commandLine.addSubcommand("help", new HelpCommand());
        commandLine.setOut(rootCommand.out());
        commandLine.setErr(rootCommand.err());
        commandLine.setUnmatchedArgumentsAllowed(false);
        CliRootCommand.configureStubParsing(commandLine);
        return commandLine;
    }

    private static void addSubcommand(CommandLine commandLine, CliCommandDescriptor descriptor) {
        commandLine.addSubcommand(descriptor.name(), instantiate(descriptor.commandClass()));
    }

    private static Object instantiate(Class<?> commandClass) {
        try {
            return commandClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot instantiate CLI command " + commandClass.getName(), exception);
        }
    }
}
