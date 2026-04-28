package me.golemcore.bot.adapter.inbound.command;

import java.util.List;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.port.inbound.CommandPort;

/**
 * Application command interactor. Inbound adapters provide a normalized
 * invocation; handlers execute use-case logic and return structured outcomes.
 */
interface CommandHandler {

    int order();

    List<String> commandNames();

    List<CommandPort.CommandDefinition> listCommands();

    CommandOutcome handle(CommandInvocation invocation);
}
