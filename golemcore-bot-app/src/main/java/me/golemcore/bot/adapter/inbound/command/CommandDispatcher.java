package me.golemcore.bot.adapter.inbound.command;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;

/**
 * Transport-neutral command registry and dispatcher.
 */
class CommandDispatcher {

    private final Map<String, CommandHandler> handlersByName;
    private final List<CommandHandler> orderedHandlers;
    private final UserPreferencesService preferencesService;

    CommandDispatcher(List<CommandHandler> handlers, UserPreferencesService preferencesService) {
        this(createRegistry(handlers), preferencesService);
    }

    private CommandDispatcher(CommandRegistry registry, UserPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
        this.orderedHandlers = registry.orderedHandlers();
        this.handlersByName = registry.handlersByName();
    }

    CommandOutcome dispatch(CommandInvocation invocation) {
        if (invocation == null || invocation.command().isBlank()) {
            return CommandOutcome.failure(msg("command.unknown", ""));
        }
        CommandHandler handler = handlersByName.get(invocation.command());
        if (handler == null) {
            return CommandOutcome.failure(msg("command.unknown", invocation.command()));
        }
        return handler.handle(invocation);
    }

    boolean hasCommand(String command) {
        return command != null && handlersByName.containsKey(command);
    }

    List<CommandPort.CommandDefinition> listCommands() {
        return orderedHandlers.stream()
                .flatMap(handler -> handler.listCommands().stream())
                .toList();
    }

    private static CommandRegistry createRegistry(List<CommandHandler> handlers) {
        List<CommandHandler> orderedHandlers = handlers.stream()
                .sorted(Comparator.comparingInt(CommandHandler::order))
                .toList();
        return new CommandRegistry(orderedHandlers, indexHandlers(orderedHandlers));
    }

    private static Map<String, CommandHandler> indexHandlers(List<CommandHandler> handlers) {
        Map<String, CommandHandler> indexed = new LinkedHashMap<>();
        for (CommandHandler handler : handlers) {
            for (String commandName : handler.commandNames()) {
                if (commandName == null || commandName.isBlank()) {
                    continue;
                }
                CommandHandler existing = indexed.putIfAbsent(commandName, handler);
                if (existing != null) {
                    throw new IllegalStateException("Duplicate command handler for /" + commandName);
                }
            }
        }
        return Map.copyOf(indexed);
    }

    private record CommandRegistry(List<CommandHandler> orderedHandlers, Map<String, CommandHandler> handlersByName) {
    }

    private String msg(String key, Object... args) {
        return preferencesService.getMessage(key, args);
    }
}
