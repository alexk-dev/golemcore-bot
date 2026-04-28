package me.golemcore.bot.domain.command;

import java.util.List;
import java.util.Locale;

/**
 * Transport-neutral command invocation passed from inbound adapters to
 * application command interactors.
 *
 * @param command
 *            normalized command name without a leading slash
 * @param args
 *            parsed command arguments
 * @param rawInput
 *            original user input when available
 * @param context
 *            typed execution context
 */
public record CommandInvocation(String command,List<String>args,String rawInput,CommandExecutionContext context){

public CommandInvocation{command=normalizeCommand(command);args=args==null||args.isEmpty()?List.of():List.copyOf(args);rawInput=rawInput==null?"":rawInput;context=context==null?CommandExecutionContext.builder().build():context;}

public static CommandInvocation of(String command,List<String>args,String rawInput,CommandExecutionContext context){return new CommandInvocation(command,args,rawInput,context);}

public static CommandInvocation fromLegacy(String command,List<String>args,java.util.Map<String,Object>context){return new CommandInvocation(command,args,"",CommandExecutionContext.fromLegacyMap(context));}

private static String normalizeCommand(String command){if(command==null||command.isBlank()){return"";}String normalized=command.trim();while(normalized.startsWith("/")){normalized=normalized.substring(1);}int botMentionIndex=normalized.indexOf('@');if(botMentionIndex>0){normalized=normalized.substring(0,botMentionIndex);}return normalized.toLowerCase(Locale.ROOT);}}
