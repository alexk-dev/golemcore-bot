package me.golemcore.bot;

import java.util.Arrays;

/**
 * Separates runtime command arguments from Spring Boot application arguments.
 */
final class BotApplicationCommandLine {

    private static final String CLI_COMMAND = "cli";
    private static final String WEB_COMMAND = "web";

    private BotApplicationCommandLine() {
    }

    static boolean isCliMode(String[] args) {
        return args != null && args.length > 0 && CLI_COMMAND.equals(args[0]);
    }

    static String[] cliArguments(String[] args) {
        if (!isCliMode(args)) {
            return new String[0];
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    static String[] springArguments(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        if (WEB_COMMAND.equals(args[0])) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return Arrays.copyOf(args, args.length);
    }
}
