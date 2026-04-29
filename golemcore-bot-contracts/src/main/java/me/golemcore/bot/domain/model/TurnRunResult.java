package me.golemcore.bot.domain.model;

import java.util.List;

/**
 * Immutable public result of an agent turn run.
 */
public record TurnRunResult(String sessionId,String runId,String traceId,RunStatus status,OutgoingResponse response,List<FailureSummary>failures,boolean stopped,boolean queued,PersistenceOutcome persistence){

public TurnRunResult{failures=failures!=null?List.copyOf(failures):List.of();}

public static TurnRunResult skipped(String sessionId,String reason){return new TurnRunResult(sessionId,null,null,RunStatus.SKIPPED,null,List.of(new FailureSummary(FailureSource.SYSTEM,"SessionRunCoordinator",FailureKind.EXCEPTION,"turn.skipped",reason,null)),false,false,PersistenceOutcome.skipped(sessionId,reason));}}
