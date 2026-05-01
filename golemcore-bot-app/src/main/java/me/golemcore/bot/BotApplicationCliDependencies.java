package me.golemcore.bot;

import me.golemcore.bot.cli.CliDependencies;
import me.golemcore.bot.cli.application.usecase.DoctorUseCase;
import me.golemcore.bot.cli.application.usecase.NotImplementedCommandUseCase;
import me.golemcore.bot.cli.application.usecase.StartTuiUseCase;
import me.golemcore.bot.cli.presentation.CommandResultPresenter;
import me.golemcore.bot.cli.presentation.DoctorPresenter;
import me.golemcore.bot.cli.router.CliCommandCatalog;

/**
 * App-owned CLI dependency assembly.
 */
final class BotApplicationCliDependencies {

    private BotApplicationCliDependencies() {
    }

    static CliDependencies create() {
        return new CliDependencies(
                new StartTuiUseCase(),
                new NotImplementedCommandUseCase(),
                new DoctorUseCase(CliCommandCatalog.subcommandNames()),
                new CommandResultPresenter(),
                new DoctorPresenter());
    }
}
