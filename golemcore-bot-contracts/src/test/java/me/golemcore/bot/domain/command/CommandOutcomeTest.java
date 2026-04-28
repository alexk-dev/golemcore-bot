package me.golemcore.bot.domain.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandOutcomeTest {

    @Test
    void shouldExposeTextOutcomeFallbackAndData() {
        Map<String, String> data = Map.of("id", "cmd-1");

        CommandOutcome outcome = CommandOutcome.text(true, "Done", data);
        CommandOutcome failure = CommandOutcome.failure("Nope");

        assertTrue(outcome.success());
        assertEquals("Done", outcome.fallbackText());
        assertEquals(data, outcome.data());
        assertFalse(failure.success());
        assertEquals("Nope", failure.fallbackText());
    }

    @Test
    void shouldRenderStructuredBlocksAsFallbackMarkdown() {
        CommandOutcome outcome = new CommandOutcome(
                true,
                List.of(
                        new CommandTextBlock("Models"),
                        new CommandTableBlock(
                                List.of("Tier", "Model"),
                                List.of(
                                        List.of("smart", "openai/gpt-5"),
                                        List.of("fast")))),
                null);

        assertEquals(
                """
                        Models

                        | Tier | Model |
                        |---|---|
                        | smart | openai/gpt-5 |
                        | fast |  |""",
                outcome.fallbackText());
    }

    @Test
    void shouldDefensivelyCopyBlocksAndRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.of("a", "b")));
        CommandTableBlock table = new CommandTableBlock(List.of("A", "B"), rows);
        rows.getFirst().add("c");

        CommandOutcome outcome = new CommandOutcome(true, List.of(table), null);

        assertEquals(List.of("a", "b"), table.rows().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> outcome.blocks().add(new CommandTextBlock("x")));
    }
}
