package me.golemcore.bot.cli.domain;

import java.util.Objects;

public record CliCommandInvocation(String commandName,CliCommandOptions options){

public CliCommandInvocation{Objects.requireNonNull(commandName,"commandName");Objects.requireNonNull(options,"options");}}
