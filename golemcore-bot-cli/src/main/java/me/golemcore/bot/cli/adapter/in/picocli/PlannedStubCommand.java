package me.golemcore.bot.cli.adapter.in.picocli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import me.golemcore.bot.cli.domain.CliExitCodes;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;
import picocli.CommandLine.Model.CommandSpec;

abstract class PlannedStubCommand implements Callable<Integer> {

    @ParentCommand
    private Object parent;

    @Spec
    private CommandSpec commandSpec;

    @Mixin
    private final CliGlobalOptions localOptions = new CliGlobalOptions();

    @Unmatched
    private final List<String> plannedArguments = new ArrayList<>();

    @Override
    public Integer call() {
        CliRootCommand rootCommand = rootCommand();
        if (rootCommand == null) {
            return CliExitCodes.GENERAL_FAILURE;
        }
        CliGlobalOptions effectiveOptions = effectiveOptions(rootCommand);
        CliOptionsValidator.validate(effectiveOptions, commandSpec.commandLine());
        rejectUnknownOptionLikeArguments();
        return rootCommand.executeStub(commandName(), effectiveOptions, plannedArguments);
    }

    private CliRootCommand rootCommand() {
        Object current = parent;
        while (current instanceof PlannedStubCommand stubCommand) {
            current = stubCommand.parent;
        }
        if (current instanceof CliRootCommand rootCommand) {
            return rootCommand;
        }
        return null;
    }

    private CliGlobalOptions effectiveOptions(CliRootCommand rootCommand) {
        CliGlobalOptions effectiveOptions = rootCommand.globalOptions();
        for (CliGlobalOptions commandOptions : commandOptionsChain()) {
            effectiveOptions = effectiveOptions.merge(commandOptions);
        }
        return effectiveOptions;
    }

    private List<CliGlobalOptions> commandOptionsChain() {
        List<CliGlobalOptions> optionsChain = new ArrayList<>();
        Object current = this;
        while (current instanceof PlannedStubCommand stubCommand) {
            optionsChain.add(0, stubCommand.localOptions);
            current = stubCommand.parent;
        }
        return optionsChain;
    }

    private String commandName() {
        String qualifiedName = commandSpec.qualifiedName();
        if (qualifiedName.startsWith("cli ")) {
            return qualifiedName.substring("cli ".length());
        }
        return commandSpec.name();
    }

    private void rejectUnknownOptionLikeArguments() {
        for (String argument : plannedArguments) {
            if (argument.length() > 1 && argument.startsWith("-")) {
                throw new CommandLine.ParameterException(commandSpec.commandLine(), "Unknown option: " + argument);
            }
        }
    }
}
