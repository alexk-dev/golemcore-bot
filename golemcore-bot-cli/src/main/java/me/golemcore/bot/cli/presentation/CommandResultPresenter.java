package me.golemcore.bot.cli.presentation;

import java.io.PrintWriter;
import me.golemcore.bot.cli.domain.CommandExecutionResult;

public final class CommandResultPresenter {

    public int render(CommandExecutionResult result, PrintWriter out, PrintWriter err) {
        if (!result.stdout().isBlank()) {
            out.println(result.stdout());
        }
        if (!result.stderr().isBlank()) {
            err.println(result.stderr());
        }
        return result.exitCode();
    }
}
