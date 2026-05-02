package me.golemcore.bot.cli;

import java.util.Objects;
import me.golemcore.bot.cli.application.port.in.CommandStubInputBoundary;
import me.golemcore.bot.cli.application.port.in.DoctorInputBoundary;
import me.golemcore.bot.cli.application.port.in.StartTuiInputBoundary;
import me.golemcore.bot.cli.application.usecase.DoctorUseCase;
import me.golemcore.bot.cli.application.usecase.NotImplementedCommandUseCase;
import me.golemcore.bot.cli.application.usecase.StartTuiUseCase;
import me.golemcore.bot.cli.presentation.CommandResultPresenter;
import me.golemcore.bot.cli.presentation.DoctorPresenter;
import me.golemcore.bot.cli.router.CliCommandCatalog;

public record CliDependencies(StartTuiInputBoundary startTuiUseCase,CommandStubInputBoundary commandStubUseCase,DoctorInputBoundary doctorUseCase,CommandResultPresenter commandResultPresenter,DoctorPresenter doctorPresenter){

public CliDependencies{Objects.requireNonNull(startTuiUseCase,"startTuiUseCase");Objects.requireNonNull(commandStubUseCase,"commandStubUseCase");Objects.requireNonNull(doctorUseCase,"doctorUseCase");Objects.requireNonNull(commandResultPresenter,"commandResultPresenter");Objects.requireNonNull(doctorPresenter,"doctorPresenter");}

public static CliDependencies defaults(){return new CliDependencies(new StartTuiUseCase(),new NotImplementedCommandUseCase(),new DoctorUseCase(CliCommandCatalog.subcommandNames()),new CommandResultPresenter(),new DoctorPresenter());}}
