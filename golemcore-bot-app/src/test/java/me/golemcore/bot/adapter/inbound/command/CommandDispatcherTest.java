package me.golemcore.bot.adapter.inbound.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import java.util.List;
import me.golemcore.bot.domain.command.CommandInvocation;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.port.inbound.CommandPort;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;

class CommandDispatcherTest {

    @Test
    void shouldDispatchToMatchingHandlerAndKeepDefinitionsOrdered() {
        CommandDispatcher dispatcher = new CommandDispatcher(
                List.of(
                        new StubHandler(20, "second", "second result"),
                        new StubHandler(10, "first", "first result")),
                preferencesService());

        CommandOutcome outcome = dispatcher.dispatch(CommandInvocation.of("second", List.of(), "", null));

        assertTrue(outcome.success());
        assertEquals("second result", outcome.fallbackText());
        assertTrue(dispatcher.hasCommand("first"));
        assertEquals(
                List.of("first", "second"),
                dispatcher.listCommands().stream()
                        .map(CommandPort.CommandDefinition::name)
                        .toList());
    }

    @Test
    void shouldReturnLocalizedFailureForUnknownCommand() {
        CommandDispatcher dispatcher = new CommandDispatcher(
                List.of(new StubHandler(10, "known", "ok")),
                preferencesService());

        CommandOutcome outcome = dispatcher.dispatch(CommandInvocation.of("missing", List.of(), "", null));

        assertTrue(!outcome.success());
        assertEquals("command.unknown missing", outcome.fallbackText());
    }

    @Test
    void shouldRejectDuplicateCommandNames() {
        UserPreferencesService preferencesService = preferencesService();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new CommandDispatcher(
                        List.of(
                                new StubHandler(10, "same", "first"),
                                new StubHandler(20, "same", "second")),
                        preferencesService));

        assertTrue(error.getMessage().contains("/same"));
    }

    @Test
    void shouldBeCreatableBySpringContainer() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withBean(UserPreferencesService.class, CommandDispatcherTest::preferencesService)
                .withBean(CommandHandler.class, () -> new StubHandler(10, "spring", "ok"))
                .withUserConfiguration(CommandDispatcherConfiguration.class);

        contextRunner.run(context -> {
            CommandDispatcher dispatcher = context.getBean(CommandDispatcher.class);

            assertNotNull(dispatcher);
            assertTrue(dispatcher.hasCommand("spring"));
        });
    }

    private static UserPreferencesService preferencesService() {
        return mock(UserPreferencesService.class, invocation -> {
            if ("getMessage".equals(invocation.getMethod().getName())) {
                Object[] allArgs = invocation.getArguments();
                StringBuilder builder = new StringBuilder((String) allArgs[0]);
                if (allArgs.length > 1 && allArgs[1] instanceof Object[] args) {
                    for (Object arg : args) {
                        builder.append(" ").append(arg);
                    }
                } else {
                    for (int index = 1; index < allArgs.length; index++) {
                        builder.append(" ").append(allArgs[index]);
                    }
                }
                return builder.toString();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private record StubHandler(int order, String commandName, String output) implements CommandHandler {

    @Override
    public List<String> commandNames() {
        return List.of(commandName);
    }

    @Override
    public List<CommandPort.CommandDefinition> listCommands() {
        return List.of(new CommandPort.CommandDefinition(commandName, "desc", "/" + commandName));
    }

    @Override
    public CommandOutcome handle(CommandInvocation invocation) {
        return CommandOutcome.success(output);
    }
}}
