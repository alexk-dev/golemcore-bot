package me.golemcore.bot.domain.command;

/**
 * Structured command output block. Presenters convert these blocks to
 * Telegram Markdown, web JSON, or future CLI ANSI/TUI output.
 */
public sealed
interface CommandBlock
permits CommandTextBlock, CommandTableBlock
{
}
