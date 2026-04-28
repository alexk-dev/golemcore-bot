package me.golemcore.bot.domain.model;

import java.time.Instant;

/**
 * Immutable public failure projection that does not expose mutable runtime
 * state.
 */
public record FailureSummary(FailureSource source,String component,FailureKind kind,String errorCode,String message,Instant timestamp){

public static FailureSummary from(FailureEvent failure){if(failure==null){return null;}return new FailureSummary(failure.source(),failure.component(),failure.kind(),null,failure.message(),failure.timestamp());}}
