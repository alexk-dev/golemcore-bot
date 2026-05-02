package me.golemcore.bot.cli.application.usecase;

import me.golemcore.bot.cli.application.port.in.CommandStubInputBoundary;
import me.golemcore.bot.cli.domain.CliExitCodes;
import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CommandExecutionResult;

public final class NotImplementedCommandUseCase implements CommandStubInputBoundary {

    @Override
    public CommandExecutionResult execute(CliCommandInvocation invocation) {
        return CommandExecutionResult.stderr(
                CliExitCodes.NOT_IMPLEMENTED,
                invocation.commandName() + " is not implemented in this CLI slice");
    }
}
