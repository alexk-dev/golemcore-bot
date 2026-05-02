package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Duration;
import java.util.List;

/**
 * User or policy decision for a CLI permission request.
 */
public record PermissionDecision(String requestId,PermissionDecisionKind decision,@JsonFormat(shape=JsonFormat.Shape.STRING)Duration duration,List<String>scopes,String reason){

public PermissionDecision{scopes=CliContractCollections.copyList(scopes);}}
