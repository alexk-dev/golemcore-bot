package me.golemcore.bot.cli;

import java.util.Objects;

public record CliInvocation(String commandName,CliOptions options){

public CliInvocation{Objects.requireNonNull(commandName,"commandName");Objects.requireNonNull(options,"options");}}
