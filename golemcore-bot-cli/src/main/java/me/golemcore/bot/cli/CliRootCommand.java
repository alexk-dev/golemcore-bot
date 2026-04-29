package me.golemcore.bot.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "cli", mixinStandardHelpOptions = true, sortOptions = false, description = {
        "Local coding-agent runtime adapter for GolemCore Bot."
}, subcommands = {
        CliRootCommand.RunCommand.class,
        CliRootCommand.ServeCommand.class,
        CliRootCommand.AttachCommand.class,
        CliRootCommand.AcpCommand.class,
        CliRootCommand.SessionCommand.class,
        CliRootCommand.AgentCommand.class,
        CliRootCommand.AuthCommand.class,
        CliRootCommand.ProvidersCommand.class,
        CliRootCommand.ModelsCommand.class,
        CliRootCommand.TierCommand.class,
        CliRootCommand.McpCommand.class,
        CliRootCommand.SkillCommand.class,
        CliRootCommand.PluginCommand.class,
        CliRootCommand.ToolCommand.class,
        CliRootCommand.PermissionsCommand.class,
        CliRootCommand.ProjectCommand.class,
        CliRootCommand.ConfigCommand.class,
        CliRootCommand.MemoryCommand.class,
        CliRootCommand.RagCommand.class,
        CliRootCommand.AutoCommand.class,
        CliRootCommand.LspCommand.class,
        CliRootCommand.TerminalCommand.class,
        CliRootCommand.GitCommand.class,
        CliRootCommand.PatchCommand.class,
        CliRootCommand.GithubCommand.class,
        CliRootCommand.TraceCommand.class,
        CliRootCommand.StatsCommand.class,
        CliRootCommand.DoctorCommand.class,
        CliRootCommand.ExportCommand.class,
        CliRootCommand.ImportCommand.class,
        CliRootCommand.CompletionCommand.class,
        CliRootCommand.UpgradeCommand.class,
        CliRootCommand.UninstallCommand.class,
        HelpCommand.class
})
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class CliRootCommand implements Callable<Integer> {

    @Mixin
    private final CliGlobalOptions globalOptions = new CliGlobalOptions();

    @Spec
    private CommandSpec spec;

    private final PrintWriter out;
    private final PrintWriter err;
    private CliInvocation invocation;

    CliRootCommand() {
        this(null, null);
    }

    CliRootCommand(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        recordInvocation("cli");
        return 0;
    }

    public CliInvocation invocation() {
        return invocation;
    }

    CliGlobalOptions globalOptions() {
        return globalOptions;
    }

    PrintWriter out() {
        if (out != null) {
            return out;
        }
        return spec.commandLine().getOut();
    }

    PrintWriter err() {
        if (err != null) {
            return err;
        }
        return spec.commandLine().getErr();
    }

    CliOptions options() {
        return new CliOptions(
                path(globalOptions.cwd()),
                path(globalOptions.project()),
                path(globalOptions.workspace()),
                path(globalOptions.config()),
                path(globalOptions.configDir()),
                globalOptions.profile(),
                path(globalOptions.envFile()),
                globalOptions.model(),
                globalOptions.tier(),
                globalOptions.agent(),
                globalOptions.session(),
                globalOptions.continueLatest(),
                globalOptions.fork(),
                globalOptions.effectiveFormat().cliValue(),
                globalOptions.json(),
                globalOptions.noColor(),
                globalOptions.color(),
                globalOptions.quiet(),
                globalOptions.verbose(),
                globalOptions.logLevel(),
                globalOptions.trace(),
                path(globalOptions.traceExport()),
                globalOptions.noMemory(),
                globalOptions.noRag(),
                globalOptions.noMcp(),
                globalOptions.noSkills(),
                globalOptions.permissionMode().cliValue(),
                globalOptions.yes(),
                globalOptions.noInput(),
                globalOptions.timeout(),
                globalOptions.maxLlmCalls(),
                globalOptions.maxToolExecutions(),
                globalOptions.attach().cliValue(),
                globalOptions.port(),
                globalOptions.hostname(),
                globalOptions.javaOptions());
    }

    void recordInvocation(String commandName) {
        invocation = new CliInvocation(commandName, options());
    }

    private static Path path(String value) {
        if (value == null) {
            return null;
        }
        return Path.of(value);
    }

    @Command(name = "run", mixinStandardHelpOptions = true, description = "Run agent non-interactively.")
    static final class RunCommand extends StubCommand {
    }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Start headless runtime server.")
    static final class ServeCommand extends StubCommand {
    }

    @Command(name = "attach", mixinStandardHelpOptions = true, description = "Attach TUI to runtime.")
    static final class AttachCommand extends StubCommand {
    }

    @Command(name = "acp", mixinStandardHelpOptions = true, description = "Start IDE/ACP stdio server.")
    static final class AcpCommand extends StubCommand {
    }

    @Command(name = "session", mixinStandardHelpOptions = true, description = "Manage sessions.", subcommands = {
            ListCommand.class
    })
    static final class SessionCommand extends StubCommand {
    }

    @Command(name = "agent", mixinStandardHelpOptions = true, description = "Manage agent profiles.", subcommands = {
            ListCommand.class
    })
    static final class AgentCommand extends StubCommand {
    }

    @Command(name = "auth", mixinStandardHelpOptions = true, description = "Manage credentials.")
    static final class AuthCommand extends StubCommand {
    }

    @Command(name = "providers", mixinStandardHelpOptions = true, description = "Manage provider definitions.")
    static final class ProvidersCommand extends StubCommand {
    }

    @Command(name = "models", mixinStandardHelpOptions = true, description = "Manage/discover models.")
    static final class ModelsCommand extends StubCommand {
    }

    @Command(name = "tier", mixinStandardHelpOptions = true, description = "Manage model tier preference.")
    static final class TierCommand extends StubCommand {
    }

    @Command(name = "mcp", mixinStandardHelpOptions = true, description = "Manage MCP servers.", subcommands = {
            ListCommand.class
    })
    static final class McpCommand extends StubCommand {
    }

    @Command(name = "skill", mixinStandardHelpOptions = true, description = "Manage skills.")
    static final class SkillCommand extends StubCommand {
    }

    @Command(name = "plugin", mixinStandardHelpOptions = true, description = "Manage plugins.")
    static final class PluginCommand extends StubCommand {
    }

    @Command(name = "tool", mixinStandardHelpOptions = true, description = "Inspect/run tools.")
    static final class ToolCommand extends StubCommand {
    }

    @Command(name = "permissions", mixinStandardHelpOptions = true, description = "Manage permission policy.")
    static final class PermissionsCommand extends StubCommand {
    }

    @Command(name = "project", mixinStandardHelpOptions = true, description = "Project config/trust/index.")
    static final class ProjectCommand extends StubCommand {
    }

    @Command(name = "config", mixinStandardHelpOptions = true, description = "Runtime/project config.")
    static final class ConfigCommand extends StubCommand {
    }

    @Command(name = "memory", mixinStandardHelpOptions = true, description = "Inspect/manage Memory V2.")
    static final class MemoryCommand extends StubCommand {
    }

    @Command(name = "rag", mixinStandardHelpOptions = true, description = "Inspect/manage RAG.")
    static final class RagCommand extends StubCommand {
    }

    @Command(name = "auto", mixinStandardHelpOptions = true, description = "Manage Auto Mode.")
    static final class AutoCommand extends StubCommand {
    }

    @Command(name = "lsp", mixinStandardHelpOptions = true, description = "LSP diagnostics/symbols.")
    static final class LspCommand extends StubCommand {
    }

    @Command(name = "terminal", mixinStandardHelpOptions = true, description = "PTY terminal sessions.")
    static final class TerminalCommand extends StubCommand {
    }

    @Command(name = "git", mixinStandardHelpOptions = true, description = "Git/checkpoint helpers.")
    static final class GitCommand extends StubCommand {
    }

    @Command(name = "patch", mixinStandardHelpOptions = true, description = "Agent patch approval/application.")
    static final class PatchCommand extends StubCommand {
    }

    @Command(name = "github", mixinStandardHelpOptions = true, description = "GitHub integration.")
    static final class GithubCommand extends StubCommand {
    }

    @Command(name = "trace", mixinStandardHelpOptions = true, description = "Trace inspect/export/replay.")
    static final class TraceCommand extends StubCommand {
    }

    @Command(name = "stats", mixinStandardHelpOptions = true, description = "Usage/cost/tool stats.")
    static final class StatsCommand extends StubCommand {
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true, description = "Environment diagnostics.")
    static final class DoctorCommand implements Callable<Integer> {

        @ParentCommand
        private CliRootCommand parent;

        @Option(names = "--json", description = "Render machine-readable output.")
        private boolean json;

        @Override
        public Integer call() {
            parent.recordInvocation("doctor");
            if (json || parent.globalOptions().effectiveFormat() == CliOutputFormat.JSON) {
                parent.out.println(
                        "{\"status\":\"ok\",\"checks\":[{\"name\":\"cli\",\"status\":\"ok\",\"message\":\"CLI adapter slice is available\"}]}");
                return 0;
            }
            parent.out.println("CLI: ok - command surface is available");
            return 0;
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Export sessions/config/etc.")
    static final class ExportCommand extends StubCommand {
    }

    @Command(name = "import", mixinStandardHelpOptions = true, description = "Import bundle.")
    static final class ImportCommand extends StubCommand {
    }

    @Command(name = "completion", mixinStandardHelpOptions = true, description = "Shell completions.")
    static final class CompletionCommand extends StubCommand {
    }

    @Command(name = "upgrade", mixinStandardHelpOptions = true, description = "Upgrade runtime/launcher.")
    static final class UpgradeCommand extends StubCommand {
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true, description = "Uninstall with data/config options.")
    static final class UninstallCommand extends StubCommand {
    }

    abstract static class StubCommand implements Callable<Integer> {

        @ParentCommand
        private Object parent;

        @Spec
        private CommandSpec commandSpec;

        @Override
        public Integer call() {
            if (parent instanceof CliRootCommand rootCommand) {
                rootCommand.recordInvocation(commandSpec.name());
            }
            return 0;
        }
    }

    @Command(name = "list", aliases = "ls", mixinStandardHelpOptions = true, description = "List resources.")
    static final class ListCommand extends StubCommand {
    }
}
