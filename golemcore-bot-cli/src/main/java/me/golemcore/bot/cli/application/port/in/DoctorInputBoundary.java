package me.golemcore.bot.cli.application.port.in;

import me.golemcore.bot.cli.domain.CliCommandOptions;
import me.golemcore.bot.cli.domain.DoctorReport;

public interface DoctorInputBoundary {

    DoctorReport inspect(CliCommandOptions options);
}
