package me.golemcore.bot;

import java.io.PrintWriter;
import me.golemcore.bot.cli.CliApplication;

/**
 * Parser-only CLI executor for help, version, completion, and lightweight
 * diagnostics.
 */
final class BotApplicationNoRuntimeCli implements BotApplicationCliRunner.NoRuntimeCli {

    @Override
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        return CliApplication.run(args, out, err, BotApplicationCliDependencies.create());
    }
}
