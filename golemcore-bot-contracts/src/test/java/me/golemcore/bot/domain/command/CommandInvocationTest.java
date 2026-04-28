package me.golemcore.bot.domain.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandInvocationTest {

    @Test
    void shouldNormalizeCommandNameAndCopyArguments() {
        List<String> args = new ArrayList<>();
        args.add("one");

        CommandInvocation invocation = CommandInvocation.of(
                "//Status@GolemBot",
                args,
                null,
                null);
        args.add("two");

        assertEquals("status", invocation.command());
        assertEquals(List.of("one"), invocation.args());
        assertEquals("", invocation.rawInput());
        assertTrue(invocation.context().toLegacyMap().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> invocation.args().add("three"));
    }

    @Test
    void shouldCreateTypedInvocationFromLegacyContext() {
        CommandInvocation invocation = CommandInvocation.fromLegacy(
                "/Plan",
                List.of("on"),
                Map.of(
                        CommandExecutionContext.KEY_SESSION_ID, "web:chat-1",
                        CommandExecutionContext.KEY_CHAT_ID, "chat-1"));

        assertEquals("plan", invocation.command());
        assertEquals(List.of("on"), invocation.args());
        assertEquals("web:chat-1", invocation.context().sessionId());
        assertEquals("chat-1", invocation.context().chatId());
    }
}
