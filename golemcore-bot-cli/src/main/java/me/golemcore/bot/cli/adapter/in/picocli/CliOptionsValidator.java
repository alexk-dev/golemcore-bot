package me.golemcore.bot.cli.adapter.in.picocli;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import me.golemcore.bot.domain.cli.CliOutputFormat;
import me.golemcore.bot.domain.cli.CliPermissionMode;
import picocli.CommandLine;

final class CliOptionsValidator {

    private CliOptionsValidator() {
    }

    static void validate(CliGlobalOptions options, CommandLine commandLine) {
        String violation = violation(options);
        if (violation != null) {
            throw new CommandLine.ParameterException(commandLine, violation);
        }
    }

    private static String violation(CliGlobalOptions options) {
        if (options.json() && options.explicitFormat() != null && options.explicitFormat() != CliOutputFormat.JSON) {
            return "--json cannot be combined with --format " + options.explicitFormat().wireValue();
        }
        if (options.quiet() && options.verbose()) {
            return "--quiet cannot be combined with --verbose";
        }
        if (options.noColor() && options.explicitColor() != null && !"never".equals(options.explicitColor())) {
            return "--no-color cannot be combined with --color " + options.explicitColor();
        }
        if (options.session() != null && options.continueLatest()) {
            return "--session cannot be combined with --continue";
        }
        if (options.session() != null && options.fork() != null) {
            return "--session cannot be combined with --fork";
        }
        if (options.continueLatest() && options.fork() != null) {
            return "--continue cannot be combined with --fork";
        }
        if (options.noInput() && options.explicitPermissionMode() == CliPermissionMode.ASK) {
            return "--no-input cannot be combined with --permission-mode ask";
        }
        if (options.port() != null && (options.port() <= 0 || options.port() > 65535)) {
            return "--port must be between 1 and 65535";
        }
        if (options.maxLlmCalls() != null && options.maxLlmCalls() < 0) {
            return "--max-llm-calls must be non-negative";
        }
        if (options.maxToolExecutions() != null && options.maxToolExecutions() < 0) {
            return "--max-tool-executions must be non-negative";
        }
        if (options.timeout() != null && !isSupportedDuration(options.timeout())) {
            return "--timeout must be an ISO-8601 duration or a human duration such as 30s, 5m, 2h, or 1d";
        }
        return null;
    }

    private static boolean isSupportedDuration(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.matches("\\d+(ms|s|m|h|d)")) {
            return true;
        }
        try {
            return !Duration.parse(value).isNegative();
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }
}
