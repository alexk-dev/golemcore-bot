package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Final result of a CLI-triggered run.
 */
public record RunResult(String sessionId,String runId,String traceId,RunStatus status,String finalResponse,Map<String,Object>usage,Map<String,Object>persistenceOutcome,CliExitCode exitCode,List<CliEvent>events,@JsonFormat(shape=JsonFormat.Shape.STRING)Instant completedAt){

public RunResult{usage=CliContractCollections.copyObjectMap(usage);persistenceOutcome=CliContractCollections.copyObjectMap(persistenceOutcome);events=CliContractCollections.copyList(events);}}
