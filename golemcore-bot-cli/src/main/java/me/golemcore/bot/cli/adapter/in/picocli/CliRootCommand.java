package me.golemcore.bot.cli.adapter.in.picocli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import me.golemcore.bot.cli.application.port.in.CommandStubInputBoundary;
import me.golemcore.bot.cli.application.port.in.DoctorInputBoundary;
import me.golemcore.bot.cli.application.port.in.StartTuiInputBoundary;
import me.golemcore.bot.cli.application.usecase.DoctorUseCase;
import me.golemcore.bot.cli.application.usecase.NotImplementedCommandUseCase;
import me.golemcore.bot.cli.application.usecase.StartTuiUseCase;
import me.golemcore.bot.cli.domain.CliExitCodes;
import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.CommandExecutionResult;
import me.golemcore.bot.cli.domain.DoctorReport;
import me.golemcore.bot.cli.presentation.CommandResultPresenter;
import me.golemcore.bot.cli.presentation.DoctorPresenter;
import me.golemcore.bot.cli.router.CliCommandCatalog;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "cli", mixinStandardHelpOptions = true, versionProvider = CliVersionProvider.class, sortOptions = false, description = {
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
    private final StartTuiInputBoundary startTuiUseCase;
    private final CommandStubInputBoundary commandStubUseCase;
    private final DoctorInputBoundary doctorUseCase;
    private final CommandResultPresenter commandResultPresenter;
    private final DoctorPresenter doctorPresenter;
    private CliCommandInvocation invocation;

    public CliRootCommand() {
        this(null, null);
    }

    public CliRootCommand(PrintWriter out, PrintWriter err) {
        this(
                out,
                err,
                new StartTuiUseCase(),
                new NotImplementedCommandUseCase(),
                new DoctorUseCase(CliCommandCatalog.subcommandNames()),
                new CommandResultPresenter(),
                new DoctorPresenter());
    }

    CliRootCommand(
            PrintWriter out,
            PrintWriter err,
            StartTuiInputBoundary startTuiUseCase,
            CommandStubInputBoundary commandStubUseCase,
            DoctorInputBoundary doctorUseCase,
            CommandResultPresenter commandResultPresenter,
            DoctorPresenter doctorPresenter) {
        this.out = out;
        this.err = err;
        this.startTuiUseCase = startTuiUseCase;
        this.commandStubUseCase = commandStubUseCase;
        this.doctorUseCase = doctorUseCase;
        this.commandResultPresenter = commandResultPresenter;
        this.doctorPresenter = doctorPresenter;
    }

    @Override
    public Integer call() {
        CliCommandInvocation currentInvocation = recordInvocation("cli");
        CommandExecutionResult result = startTuiUseCase.start(currentInvocation);
        return commandResultPresenter.render(result, out(), err());
    }

    public CliCommandInvocation invocation() {
        return invocation;
    }

    public CliGlobalOptions globalOptions() {
        return globalOptions;
    }

    public PrintWriter out() {
        if (out != null) {
            return out;
        }
        return spec.commandLine().getOut();
    }

    public PrintWriter err() {
        if (err != null) {
            return err;
        }
        return spec.commandLine().getErr();
    }

    CliCommandOptions options() {
        return new CliCommandOptions(
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
                globalOptions.effectiveFormat().wireValue(),
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
                globalOptions.permissionMode().wireValue(),
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

    CliCommandInvocation recordInvocation(String commandName) {
        invocation = new CliCommandInvocation(commandName, options());
        return invocation;
    }

    int executeStub(String commandName) {
        CliCommandInvocation currentInvocation = recordInvocation(commandName);
        CommandExecutionResult result = commandStubUseCase.execute(currentInvocation);
        return commandResultPresenter.render(result, out(), err());
    }

    DoctorReport doctorReport() {
        return doctorUseCase.inspect(options());
    }

    void renderDoctor(DoctorReport report, boolean json) {
        if (json || globalOptions.effectiveFormat() == CliOutputFormat.JSON) {
            doctorPresenter.renderJson(report, out());
            return;
        }
        doctorPresenter.renderText(report, out());
    }

    private static Path path(String value) {
        if (value == null) {
            return null;
        }
        return Path.of(value);
    }

    @Command(name = "run", mixinStandardHelpOptions = true, description = "Run agent non-interactively.")
    public static final class RunCommand extends StubCommand {
    }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Start headless runtime server.")
    public static final class ServeCommand extends StubCommand {
    }

    @Command(name = "attach", mixinStandardHelpOptions = true, description = "Attach TUI to runtime.")
    public static final class AttachCommand extends StubCommand {
    }

    @Command(name = "acp", mixinStandardHelpOptions = true, description = "Start IDE/ACP stdio server.")
    public static final class AcpCommand extends StubCommand {
    }

    @Command(name = "session", mixinStandardHelpOptions = true, description = "Manage sessions.", subcommands = {
            ListCommand.class
    })
    public static final class SessionCommand extends StubCommand {
    }

    @Command(name = "agent", mixinStandardHelpOptions = true, description = "Manage agent profiles.", subcommands = {
            ListCommand.class
    })
    public static final class AgentCommand extends StubCommand {
    }

    @Command(name = "auth", mixinStandardHelpOptions = true, description = "Manage credentials.")
    public static final class AuthCommand extends StubCommand {
    }

    @Command(name = "providers", mixinStandardHelpOptions = true, description = "Manage provider definitions.")
    public static final class ProvidersCommand extends StubCommand {
    }

    @Command(name = "models", mixinStandardHelpOptions = true, description = "Manage/discover models.")
    public static final class ModelsCommand extends StubCommand {
    }

    @Command(name = "tier", mixinStandardHelpOptions = true, description = "Manage model tier preference.")
    public static final class TierCommand extends StubCommand {
    }

    @Command(name = "mcp", mixinStandardHelpOptions = true, description = "Manage MCP servers.", subcommands = {
            ListCommand.class
    })
    public static final class McpCommand extends StubCommand {
    }

    @Command(name = "skill", mixinStandardHelpOptions = true, description = "Manage skills.")
    public static final class SkillCommand extends StubCommand {
    }

    @Command(name = "plugin", mixinStandardHelpOptions = true, description = "Manage plugins.")
    public static final class PluginCommand extends StubCommand {
    }

    @Command(name = "tool", mixinStandardHelpOptions = true, description = "Inspect/run tools.")
    public static final class ToolCommand extends StubCommand {
    }

    @Command(name = "permissions", mixinStandardHelpOptions = true, description = "Manage permission policy.")
    public static final class PermissionsCommand extends StubCommand {
    }

    @Command(name = "project", mixinStandardHelpOptions = true, description = "Project config/trust/index.")
    public static final class ProjectCommand extends StubCommand {
    }

    @Command(name = "config", mixinStandardHelpOptions = true, description = "Runtime/project config.")
    public static final class ConfigCommand extends StubCommand {
    }

    @Command(name = "memory", mixinStandardHelpOptions = true, description = "Inspect/manage Memory V2.")
    public static final class MemoryCommand extends StubCommand {
    }

    @Command(name = "rag", mixinStandardHelpOptions = true, description = "Inspect/manage RAG.")
    public static final class RagCommand extends StubCommand {
    }

    @Command(name = "auto", mixinStandardHelpOptions = true, description = "Manage Auto Mode.")
    public static final class AutoCommand extends StubCommand {
    }

    @Command(name = "lsp", mixinStandardHelpOptions = true, description = "LSP diagnostics/symbols.")
    public static final class LspCommand extends StubCommand {
    }

    @Command(name = "terminal", mixinStandardHelpOptions = true, description = "PTY terminal sessions.")
    public static final class TerminalCommand extends StubCommand {
    }

    @Command(name = "git", mixinStandardHelpOptions = true, description = "Git/checkpoint helpers.")
    public static final class GitCommand extends StubCommand {
    }

    @Command(name = "patch", mixinStandardHelpOptions = true, description = "Agent patch approval/application.")
    public static final class PatchCommand extends StubCommand {
    }

    @Command(name = "github", mixinStandardHelpOptions = true, description = "GitHub integration.")
    public static final class GithubCommand extends StubCommand {
    }

    @Command(name = "trace", mixinStandardHelpOptions = true, description = "Trace inspect/export/replay.")
    public static final class TraceCommand extends StubCommand {
    }

    @Command(name = "stats", mixinStandardHelpOptions = true, description = "Usage/cost/tool stats.")
    public static final class StatsCommand extends StubCommand {
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true, description = "Environment diagnostics.")
    public static final class DoctorCommand implements Callable<Integer> {

        @ParentCommand
        private CliRootCommand parent;

        @Option(names = "--json", description = "Render machine-readable output.")
        private boolean json;

        @Override
        public Integer call() {
            parent.recordInvocation("doctor");
            parent.renderDoctor(parent.doctorReport(), json);
            return CliExitCodes.SUCCESS;
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Export sessions/config/etc.")
    public static final class ExportCommand extends StubCommand {
    }

    @Command(name = "import", mixinStandardHelpOptions = true, description = "Import bundle.")
    public static final class ImportCommand extends StubCommand {
    }

    @Command(name = "completion", mixinStandardHelpOptions = true, description = "Shell completions.")
    public static final class CompletionCommand extends StubCommand {
    }

    @Command(name = "upgrade", mixinStandardHelpOptions = true, description = "Upgrade runtime/launcher.")
    public static final class UpgradeCommand extends StubCommand {
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true, description = "Uninstall with data/config options.")
    public static final class UninstallCommand extends StubCommand {
    }

    abstract static class StubCommand implements Callable<Integer> {

        @ParentCommand
        private Object parent;

        @Spec
        private CommandSpec commandSpec;

        @Override
        public Integer call() {
            CliRootCommand rootCommand = rootCommand();
            if (rootCommand == null) {
                return CliExitCodes.GENERAL_FAILURE;
            }
            return rootCommand.executeStub(commandName());
        }

        private CliRootCommand rootCommand() {
            Object current = parent;
            while (current instanceof StubCommand stubCommand) {
                current = stubCommand.parent;
            }
            if (current instanceof CliRootCommand rootCommand) {
                return rootCommand;
            }
            return null;
        }

        private String commandName() {
            String qualifiedName = commandSpec.qualifiedName();
            if (qualifiedName.startsWith("cli ")) {
                return qualifiedName.substring("cli ".length());
            }
            return commandSpec.name();
        }
    }

    @Command(name = "list", aliases = "ls", mixinStandardHelpOptions = true, description = "List resources.")
    public static final class ListCommand extends StubCommand {
    }
}
