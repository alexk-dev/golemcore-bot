package me.golemcore.bot.adapter.inbound.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlashCommandParserTest {

    private final SlashCommandParser parser = new SlashCommandParser();

    @Test
    void shouldIgnoreNonCommandInput() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("hello").isEmpty());
        assertTrue(parser.parse("/").isEmpty());
    }

    @Test
    void shouldNormalizeCommandAndStripBotMention() {
        Optional<ParsedSlashCommand> parsed = parser.parse("  /Status@GolemBot  ");

        assertTrue(parsed.isPresent());
        assertEquals("status", parsed.get().command());
        assertEquals(List.of(), parsed.get().args());
        assertEquals("/Status@GolemBot", parsed.get().rawInput());
    }

    @Test
    void shouldParseQuotedAndEscapedArguments() {
        Optional<ParsedSlashCommand> parsed = parser.parse(
                "/goal Build\\ API \"ship v1\" 'two words' quote\\\"");

        assertTrue(parsed.isPresent());
        assertEquals("goal", parsed.get().command());
        assertEquals(
                List.of("Build API", "ship v1", "two words", "quote\""),
                parsed.get().args());
    }

    @Test
    void shouldPreserveUnquotedApostrophesAndWindowsPaths() {
        Optional<ParsedSlashCommand> parsed = parser.parse(
                "/goal John's task C:\\tmp\\project \"D:\\Work Folder\\file.txt\"");

        assertTrue(parsed.isPresent());
        assertEquals("goal", parsed.get().command());
        assertEquals(
                List.of("John's", "task", "C:\\tmp\\project", "D:\\Work Folder\\file.txt"),
                parsed.get().args());
    }
}
