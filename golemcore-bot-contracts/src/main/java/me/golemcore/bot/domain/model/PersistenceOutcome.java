package me.golemcore.bot.domain.model;

/**
 * Structured final persistence result for a turn.
 */
public record PersistenceOutcome(boolean saved,String sessionId,String errorCode,String errorMessage){

public static PersistenceOutcome saved(String sessionId){return new PersistenceOutcome(true,sessionId,null,null);}

public static PersistenceOutcome failed(String sessionId,String errorCode,String errorMessage){return new PersistenceOutcome(false,sessionId,errorCode,errorMessage);}

public static PersistenceOutcome skipped(String sessionId,String reason){return new PersistenceOutcome(false,sessionId,"persistence.skipped",reason);}}
