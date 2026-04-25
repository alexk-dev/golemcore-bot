package me.golemcore.bot.domain.model;

import java.util.Map;

/**
 * Normalized structured trace of a single tool execution, used for progress
 * summaries without exposing raw tool output to the user.
 *
 * @param toolName
 *            executed tool name
 * @param family
 *            high-level tool family (shell, filesystem, search, browse, ...)
 * @param action
 *            concise human-readable action description
 * @param success
 *            whether the tool succeeded
 * @param durationMs
 *            execution duration
 * @param details
 *            structured details used by summarization and transports
 */
public record ToolExecutionTrace(String toolName,String family,String action,boolean success,long durationMs,Map<String,Object>details){

public ToolExecutionTrace{details=details!=null?Map.copyOf(details):Map.of();toolName=toolName!=null?toolName:"tool";family=family!=null?family:"tool";action=action!=null?action:toolName;}}
