package me.golemcore.bot.adapter.inbound.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Shared slash-command parser for inbound transports.
 */
@Component
public class SlashCommandParser {

    public Optional<ParsedSlashCommand> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("/") || trimmed.length() == 1) {
            return Optional.empty();
        }

        int commandEnd = findCommandEnd(trimmed);
        String command = normalizeCommand(trimmed.substring(1, commandEnd));
        if (command.isBlank()) {
            return Optional.empty();
        }
        String argsText = commandEnd < trimmed.length() ? trimmed.substring(commandEnd).trim() : "";
        return Optional.of(new ParsedSlashCommand(command, parseArgs(argsText), trimmed));
    }

    private int findCommandEnd(String text) {
        int index = 1;
        while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private String normalizeCommand(String command) {
        int botMentionIndex = command.indexOf('@');
        String normalized = botMentionIndex > 0 ? command.substring(0, botMentionIndex) : command;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> parseArgs(String argsText) {
        if (argsText == null || argsText.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < argsText.length(); index++) {
            char ch = argsText.charAt(index);
            if (quote != 0) {
                if (ch == '\\' && index + 1 < argsText.length()) {
                    char next = argsText.charAt(index + 1);
                    if (next == quote) {
                        current.append(next);
                        index++;
                        continue;
                    }
                }
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\\' && index + 1 < argsText.length()) {
                char next = argsText.charAt(index + 1);
                if (Character.isWhitespace(next) || next == '"' || next == '\'') {
                    current.append(next);
                    index++;
                    continue;
                }
            }
            if (current.isEmpty() && (ch == '"' || ch == '\'')) {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                flushArg(args, current);
                continue;
            }
            current.append(ch);
        }
        flushArg(args, current);
        return List.copyOf(args);
    }

    private void flushArg(List<String> args, StringBuilder current) {
        if (current.length() > 0) {
            args.add(current.toString());
            current.setLength(0);
        }
    }
}
