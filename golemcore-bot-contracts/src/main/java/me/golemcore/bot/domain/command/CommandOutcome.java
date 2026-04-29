package me.golemcore.bot.domain.command;

import java.util.List;
import java.util.StringJoiner;

/**
 * Structured command result. Existing transports can render the fallback text,
 * while richer surfaces can inspect blocks and data directly.
 *
 * @param success
 *            whether the command completed successfully
 * @param blocks
 *            structured user-facing output blocks
 * @param data
 *            optional machine-readable payload
 */
public record CommandOutcome(boolean success,List<CommandBlock>blocks,Object data){

public CommandOutcome{blocks=blocks==null||blocks.isEmpty()?List.of():List.copyOf(blocks);}

public static CommandOutcome success(String text){return text(true,text,null);}

public static CommandOutcome failure(String text){return text(false,text,null);}

public static CommandOutcome text(boolean success,String text,Object data){return new CommandOutcome(success,List.of(new CommandTextBlock(text)),data);}

public String fallbackText(){StringJoiner joiner=new StringJoiner("\n\n");for(CommandBlock block:blocks){String rendered=renderFallbackBlock(block);if(!rendered.isBlank()){joiner.add(rendered);}}return joiner.toString();}

private static String renderFallbackBlock(CommandBlock block){if(block instanceof CommandTextBlock textBlock){return textBlock.text();}if(block instanceof CommandTableBlock tableBlock){return renderFallbackTable(tableBlock);}return"";}

private static String renderFallbackTable(CommandTableBlock tableBlock){if(tableBlock.columns().isEmpty()){return"";}StringBuilder builder=new StringBuilder();builder.append("| ").append(String.join(" | ",tableBlock.columns())).append(" |\n");builder.append("|");for(int index=0;index<tableBlock.columns().size();index++){builder.append("---|");}builder.append("\n");for(List<String>row:tableBlock.rows()){builder.append("| ");for(int index=0;index<tableBlock.columns().size();index++){if(index>0){builder.append(" | ");}builder.append(index<row.size()?row.get(index):"");}builder.append(" |\n");}return builder.toString().trim();}}
