package me.golemcore.bot.adapter.inbound.command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.stereotype.Component;

/**
 * Backward-compatible command port facade. Transport adapters should send typed
 * invocations; legacy plugin and test callers can still use
 * command/args/context triples.
 */
@Component
@Slf4j
public class CommandRouter implements CommandPort {

    private final CommandDispatcher dispatcher;

    public CommandRouter(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        log.info("CommandRouter initialized with commands: {}", dispatcher.listCommands().stream()
                .map(CommandDefinition::name)
                .toList());
    }

    @Override
    public CompletableFuture<CommandOutcome> execute(CommandInvocation invocation) {
        return CompletableFuture.supplyAsync(() -> dispatcher.dispatch(invocation));
    }

    @Override
    public CompletableFuture<CommandResult> execute(String command, List<String> args, Map<String, Object> context) {
        CommandInvocation invocation = CommandInvocation.fromLegacy(command, args, context);
        return execute(invocation).thenApply(CommandResult::fromOutcome);
    }

    @Override
    public boolean hasCommand(String command) {
        return dispatcher.hasCommand(command);
    }

    @Override
    public List<CommandDefinition> listCommands() {
        return dispatcher.listCommands();
    }
}
