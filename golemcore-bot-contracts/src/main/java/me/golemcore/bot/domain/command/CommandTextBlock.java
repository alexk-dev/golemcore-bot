package me.golemcore.bot.domain.command;

/**
 * Plain text or Markdown-compatible text block.
 *
 * @param text
 *            user-facing text
 */
public record CommandTextBlock(String text)implements CommandBlock{

public CommandTextBlock{text=text==null?"":text;}}
