package me.golemcore.bot.cli.application.port.in;

import me.golemcore.bot.cli.domain.CliCommandInvocation;
import me.golemcore.bot.cli.domain.CommandExecutionResult;

public interface CliCommandInputBoundary {

    CommandExecutionResult execute(CliCommandInvocation invocation);
}
