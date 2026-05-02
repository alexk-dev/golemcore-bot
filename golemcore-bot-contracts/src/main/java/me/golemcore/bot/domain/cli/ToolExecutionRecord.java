package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Duration;
import java.util.Map;

/**
 * Immutable summary of a tool execution visible in CLI event streams.
 */
public record ToolExecutionRecord(String tool,String argsSummary,ToolExecutionStatus status,@JsonFormat(shape=JsonFormat.Shape.STRING)Duration duration,String outputSummary,Map<String,String>sensitiveRedactions,Map<String,Object>metadata){

public ToolExecutionRecord{sensitiveRedactions=CliContractCollections.copyStringMap(sensitiveRedactions);metadata=CliContractCollections.copyObjectMap(metadata);}}
