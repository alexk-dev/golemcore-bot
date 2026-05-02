package me.golemcore.bot.cli.domain;

import java.util.Objects;

public record CommandExecutionResult(int exitCode,String stdout,String stderr){

public CommandExecutionResult{Objects.requireNonNull(stdout,"stdout");Objects.requireNonNull(stderr,"stderr");}

public static CommandExecutionResult stdout(int exitCode,String message){return new CommandExecutionResult(exitCode,message,"");}

public static CommandExecutionResult stderr(int exitCode,String message){return new CommandExecutionResult(exitCode,"",message);}}
