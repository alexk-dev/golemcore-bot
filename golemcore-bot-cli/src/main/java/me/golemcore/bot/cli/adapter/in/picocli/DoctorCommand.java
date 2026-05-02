package me.golemcore.bot.cli.adapter.in.picocli;

import java.util.concurrent.Callable;
import me.golemcore.bot.cli.domain.CliExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "doctor", mixinStandardHelpOptions = true, description = "Environment diagnostics.")
public final class DoctorCommand implements Callable<Integer> {

    @ParentCommand
    private CliRootCommand parent;

    @Spec
    private CommandSpec commandSpec;

    @Mixin
    private final CliGlobalOptions localOptions = new CliGlobalOptions();

    @Override
    public Integer call() {
        CliGlobalOptions effectiveOptions = parent.globalOptions().merge(localOptions);
        CliOptionsValidator.validate(effectiveOptions, commandSpec.commandLine());
        parent.recordInvocation("doctor", effectiveOptions);
        parent.renderDoctor(parent.doctorReport(effectiveOptions), effectiveOptions);
        return CliExitCodes.SUCCESS;
    }
}
