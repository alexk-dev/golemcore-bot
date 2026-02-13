package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolResult;

/**
 * Result of a single tool execution (real or synthetic).
 *
 * @param toolCallId
 *            tool_call_id as provided by the LLM
 * @param toolName
 *            tool name (as used in history)
 * @param toolResult
 *            raw ToolResult (success/failure + structured data)
 * @param messageContent
 *            content to write into the "tool" message (possibly truncated)
 * @param synthetic
 *            whether this result was produced without executing the tool
 */
public record ToolExecutionOutcome(String toolCallId,String toolName,ToolResult toolResult,String messageContent,boolean synthetic){

public static ToolExecutionOutcome synthetic(Message.ToolCall toolCall,String reason){return new ToolExecutionOutcome(toolCall.getId(),toolCall.getName(),ToolResult.failure(reason),reason,true);}}
