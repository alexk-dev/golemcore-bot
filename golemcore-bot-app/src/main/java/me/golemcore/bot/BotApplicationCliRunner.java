package me.golemcore.bot;

import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;

/**
 * Selects the lightweight parser-only CLI path or the CLI runtime bootstrap.
 */
final class BotApplicationCliRunner {

    private final NoRuntimeCli noRuntimeCli;
    private final RuntimeCli runtimeCli;

    BotApplicationCliRunner(NoRuntimeCli noRuntimeCli, RuntimeCli runtimeCli) {
        this.noRuntimeCli = noRuntimeCli;
        this.runtimeCli = runtimeCli;
    }

    int runCli(String[] cliArgs, PrintWriter out, PrintWriter err) {
        if (requiresRuntime(cliArgs)) {
            return runtimeCli.run(cliArgs, out, err);
        }
        return noRuntimeCli.run(cliArgs, out, err);
    }

    WebApplicationType runtimeWebApplicationType() {
        return runtimeCli.webApplicationType();
    }

    private static boolean requiresRuntime(String[] cliArgs) {
        if (cliArgs == null || cliArgs.length == 0) {
            return true;
        }
        if (Arrays.stream(cliArgs).anyMatch(BotApplicationCliRunner::isNoRuntimeFlag)) {
            return false;
        }
        String command = firstCommandToken(cliArgs);
        return switch (command) {
        case "completion", "doctor", "help" -> false;
        default -> true;
        };
    }

    private static boolean isNoRuntimeFlag(String argument) {
        return "-h".equals(argument) || "--help".equals(argument) || "--version".equals(argument);
    }

    private static String firstCommandToken(String[] cliArgs) {
        for (String arg : cliArgs) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (!arg.startsWith("-")) {
                return arg;
            }
        }
        return "";
    }

    @FunctionalInterface
    interface NoRuntimeCli {
        int run(String[] args, PrintWriter out, PrintWriter err);
    }

    interface RuntimeCli {
        int run(String[] args, PrintWriter out, PrintWriter err);

        WebApplicationType webApplicationType();
    }
}
