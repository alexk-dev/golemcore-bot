package me.golemcore.bot.adapter.inbound.command;

import me.golemcore.bot.domain.command.CommandOutcome;
import org.springframework.stereotype.Component;

/**
 * Default presenter preserving the existing Markdown-compatible command output
 * used by Telegram and the dashboard chat.
 */
@Component
class MarkdownCommandOutcomePresenter implements CommandOutcomePresenter {

    @Override
    public String present(CommandOutcome outcome) {
        if (outcome == null) {
            return "";
        }
        return outcome.fallbackText();
    }
}
