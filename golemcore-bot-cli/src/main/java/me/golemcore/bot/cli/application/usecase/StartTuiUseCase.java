package me.golemcore.bot.cli.application.usecase;

import me.golemcore.bot.cli.application.port.in.StartTuiInputBoundary;
import me.golemcore.bot.cli.domain.CliExitCodes;
import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CommandExecutionResult;

public final class StartTuiUseCase implements StartTuiInputBoundary {

    @Override
    public CommandExecutionResult start(CliCommandInvocation invocation) {
        return CommandExecutionResult.stderr(
                CliExitCodes.NOT_IMPLEMENTED,
                "TUI runtime is not implemented in this CLI slice");
    }
}
