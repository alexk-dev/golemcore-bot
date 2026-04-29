package me.golemcore.bot.domain.cli;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event envelope emitted to CLI clients.
 */
public record CliEvent(CliEventType type,String runId,String sessionId,String traceId,Instant timestamp,CliEventSeverity severity,Map<String,Object>payload){

public CliEvent{payload=CliContractCollections.copyObjectMap(payload);}}
