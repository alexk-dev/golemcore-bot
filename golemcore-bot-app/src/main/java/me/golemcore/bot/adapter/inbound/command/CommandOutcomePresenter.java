package me.golemcore.bot.adapter.inbound.command;

import me.golemcore.bot.domain.command.CommandOutcome;

/**
 * Presents structured command outcomes for a concrete surface.
 */
public interface CommandOutcomePresenter {

    String present(CommandOutcome outcome);
}
