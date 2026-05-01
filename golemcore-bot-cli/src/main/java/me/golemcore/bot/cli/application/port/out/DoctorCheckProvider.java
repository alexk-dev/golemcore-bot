package me.golemcore.bot.cli.application.port.out;

import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.DoctorCheck;

@FunctionalInterface
public interface DoctorCheckProvider {

    DoctorCheck inspect(CliCommandOptions options);
}
