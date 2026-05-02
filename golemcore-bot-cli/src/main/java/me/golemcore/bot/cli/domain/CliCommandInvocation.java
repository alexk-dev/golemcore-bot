package me.golemcore.bot.cli.domain;

import java.util.List;
import java.util.Objects;

public record CliCommandInvocation(String commandName,CliCommandOptions options,List<String>rawArguments){

public CliCommandInvocation{Objects.requireNonNull(commandName,"commandName");Objects.requireNonNull(options,"options");rawArguments=List.copyOf(rawArguments);}

public CliCommandInvocation(String commandName,CliCommandOptions options){this(commandName,options,List.of());}}
