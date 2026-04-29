package me.golemcore.bot.cli;

import java.util.List;

import picocli.CommandLine;

public final class CliCommandFactory {

    private static final List<String> SUBCOMMAND_NAMES = List.of(
            "run",
            "serve",
            "attach",
            "acp",
            "session",
            "agent",
            "auth",
            "providers",
            "models",
            "tier",
            "mcp",
            "skill",
            "plugin",
            "tool",
            "permissions",
            "project",
            "config",
            "memory",
            "rag",
            "auto",
            "lsp",
            "terminal",
            "git",
            "patch",
            "github",
            "trace",
            "stats",
            "doctor",
            "export",
            "import",
            "completion",
            "upgrade",
            "uninstall");

    private CliCommandFactory() {
    }

    public static CommandLine create() {
        return create(new CliRootCommand());
    }

    public static CommandLine create(CliRootCommand rootCommand) {
        return CliApplication.commandLine(rootCommand);
    }

    public static List<String> subcommandNames() {
        return SUBCOMMAND_NAMES;
    }
}
