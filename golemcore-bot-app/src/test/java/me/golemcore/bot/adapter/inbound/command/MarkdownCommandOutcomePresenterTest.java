package me.golemcore.bot.adapter.inbound.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.golemcore.bot.domain.command.CommandOutcome;
import me.golemcore.bot.domain.command.CommandTableBlock;
import org.junit.jupiter.api.Test;

class MarkdownCommandOutcomePresenterTest {

    private final MarkdownCommandOutcomePresenter presenter = new MarkdownCommandOutcomePresenter();

    @Test
    void shouldRenderFallbackText() {
        CommandOutcome outcome = new CommandOutcome(
                true,
                List.of(new CommandTableBlock(List.of("Name"), List.of(List.of("status")))),
                null);

        assertEquals(
                """
                        | Name |
                        |---|
                        | status |""",
                presenter.present(outcome));
    }

    @Test
    void shouldRenderNullOutcomeAsEmptyText() {
        assertEquals("", presenter.present(null));
    }
}
