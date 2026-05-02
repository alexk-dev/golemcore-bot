package me.golemcore.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BotApplicationCommandLineTest {

    @Test
    void shouldDetectCliModeAndStripCliCommand() {
        String[] args = { "cli", "doctor", "--json" };

        assertTrue(BotApplicationCommandLine.isCliMode(args));
        assertArrayEquals(new String[] { "doctor", "--json" }, BotApplicationCommandLine.cliArguments(args));
    }

    @Test
    void shouldKeepSpringArgumentsWhenNoRuntimeCommandIsPresent() {
        String[] args = { "--server.port=9090", "--spring.main.banner-mode=off" };

        assertFalse(BotApplicationCommandLine.isCliMode(args));
        assertArrayEquals(args, BotApplicationCommandLine.springArguments(args));
    }

    @Test
    void shouldStripWebCommandBeforeStartingSpringRuntime() {
        String[] args = { "web", "--server.port=9090" };

        assertFalse(BotApplicationCommandLine.isCliMode(args));
        assertArrayEquals(new String[] { "--server.port=9090" }, BotApplicationCommandLine.springArguments(args));
    }

    @Test
    void shouldHandleMissingArgumentsAsWebRuntimeArguments() {
        assertFalse(BotApplicationCommandLine.isCliMode(null));
        assertArrayEquals(new String[0], BotApplicationCommandLine.springArguments(null));
        assertArrayEquals(new String[0], BotApplicationCommandLine.cliArguments(null));
    }
}
