package me.golemcore.bot.cli.router;

import java.util.List;

public final class CliCommandCatalog {

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

    private CliCommandCatalog() {
    }

    public static List<String> subcommandNames() {
        return SUBCOMMAND_NAMES;
    }
}
