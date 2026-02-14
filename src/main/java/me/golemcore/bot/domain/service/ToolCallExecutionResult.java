package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ToolResult;

/** Output of executing a single tool call (no history mutation). */
public record ToolCallExecutionResult(String toolCallId,String toolName,ToolResult toolResult,String toolMessageContent,Attachment extractedAttachment){}
