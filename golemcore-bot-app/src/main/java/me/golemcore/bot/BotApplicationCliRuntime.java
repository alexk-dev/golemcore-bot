package me.golemcore.bot;

import java.io.PrintWriter;
import me.golemcore.bot.cli.CliApplication;
import org.springframework.boot.WebApplicationType;

/**
 * Placeholder for CLI runtime composition owned by the app module.
 */
final class BotApplicationCliRuntime implements BotApplicationCliRunner.RuntimeCli {

    @Override
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        return CliApplication.run(args, out, err, BotApplicationCliDependencies.create());
    }

    @Override
    public WebApplicationType webApplicationType() {
        return WebApplicationType.NONE;
    }
}
