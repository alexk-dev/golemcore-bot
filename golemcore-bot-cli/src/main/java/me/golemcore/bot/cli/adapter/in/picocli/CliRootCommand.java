package me.golemcore.bot.cli.adapter.in.picocli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import me.golemcore.bot.cli.CliDependencies;
import me.golemcore.bot.cli.application.port.in.CommandStubInputBoundary;
import me.golemcore.bot.cli.application.port.in.DoctorInputBoundary;
import me.golemcore.bot.cli.application.port.in.StartTuiInputBoundary;
import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.CommandExecutionResult;
import me.golemcore.bot.cli.domain.DoctorReport;
import me.golemcore.bot.cli.presentation.CommandResultPresenter;
import me.golemcore.bot.cli.presentation.DoctorPresenter;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "cli", mixinStandardHelpOptions = true, versionProvider = CliVersionProvider.class, sortOptions = false, description = {
        "Local coding-agent runtime adapter for GolemCore Bot."
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
        this(out, err, CliDependencies.defaults());
    }

    public CliRootCommand(PrintWriter out, PrintWriter err, CliDependencies dependencies) {
        this(
                out,
                err,
                dependencies.startTuiUseCase(),
                dependencies.commandStubUseCase(),
                dependencies.doctorUseCase(),
                dependencies.commandResultPresenter(),
                dependencies.doctorPresenter());
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
        CliOptionsValidator.validate(globalOptions, spec.commandLine());
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

    public static void configureStubParsing(picocli.CommandLine commandLine) {
        commandLine.getSubcommands().values().forEach(CliRootCommand::configureStubParsing);
        if (commandLine.getCommandSpec().userObject() instanceof PlannedStubCommand
                && commandLine.getSubcommands().isEmpty()) {
            commandLine.setUnmatchedArgumentsAllowed(true);
        }
    }

    CliCommandOptions options() {
        return options(globalOptions);
    }

    CliCommandOptions options(CliGlobalOptions sourceOptions) {
        return new CliCommandOptions(
                new CliCommandOptions.ProjectOptions(
                        path(sourceOptions.cwd()),
                        path(sourceOptions.project()),
                        path(sourceOptions.workspace()),
                        path(sourceOptions.config()),
                        path(sourceOptions.configDir()),
                        sourceOptions.profile(),
                        path(sourceOptions.envFile())),
                new CliCommandOptions.RuntimeSelectionOptions(
                        sourceOptions.model(),
                        sourceOptions.tier(),
                        sourceOptions.agent(),
                        sourceOptions.session(),
                        sourceOptions.continueLatest(),
                        sourceOptions.fork()),
                new CliCommandOptions.OutputOptions(
                        sourceOptions.effectiveFormat().wireValue(),
                        sourceOptions.json(),
                        sourceOptions.noColor(),
                        sourceOptions.color(),
                        sourceOptions.quiet(),
                        sourceOptions.verbose(),
                        sourceOptions.logLevel()),
                new CliCommandOptions.TraceOptions(
                        sourceOptions.trace(),
                        path(sourceOptions.traceExport())),
                new CliCommandOptions.CapabilityOptions(
                        sourceOptions.noMemory(),
                        sourceOptions.noRag(),
                        sourceOptions.noMcp(),
                        sourceOptions.noSkills()),
                new CliCommandOptions.PermissionOptions(
                        sourceOptions.permissionMode().wireValue(),
                        sourceOptions.yes(),
                        sourceOptions.noInput()),
                new CliCommandOptions.BudgetOptions(
                        sourceOptions.timeout(),
                        sourceOptions.maxLlmCalls(),
                        sourceOptions.maxToolExecutions()),
                new CliCommandOptions.AttachOptions(
                        sourceOptions.attach().cliValue(),
                        sourceOptions.port(),
                        sourceOptions.hostname()));
    }

    CliCommandInvocation recordInvocation(String commandName) {
        return recordInvocation(commandName, globalOptions);
    }

    CliCommandInvocation recordInvocation(String commandName, CliGlobalOptions sourceOptions) {
        return recordInvocation(commandName, sourceOptions, List.of());
    }

    CliCommandInvocation recordInvocation(String commandName, CliGlobalOptions sourceOptions,
            List<String> rawArguments) {
        invocation = new CliCommandInvocation(commandName, options(sourceOptions), rawArguments);
        return invocation;
    }

    int executeStub(String commandName, CliGlobalOptions sourceOptions) {
        return executeStub(commandName, sourceOptions, List.of());
    }

    int executeStub(String commandName, CliGlobalOptions sourceOptions, List<String> rawArguments) {
        CliCommandInvocation currentInvocation = recordInvocation(commandName, sourceOptions, rawArguments);
        CommandExecutionResult result = commandStubUseCase.execute(currentInvocation);
        return commandResultPresenter.render(result, out(), err());
    }

    DoctorReport doctorReport(CliGlobalOptions sourceOptions) {
        return doctorUseCase.inspect(options(sourceOptions));
    }

    void renderDoctor(DoctorReport report, CliGlobalOptions sourceOptions) {
        if (sourceOptions.effectiveFormat() == CliOutputFormat.JSON) {
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

}
