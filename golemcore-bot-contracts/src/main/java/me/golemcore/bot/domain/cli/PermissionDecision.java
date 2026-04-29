package me.golemcore.bot.domain.cli;

import java.time.Duration;
import java.util.List;

/**
 * User or policy decision for a CLI permission request.
 */
public record PermissionDecision(String requestId,PermissionDecisionKind decision,Duration duration,List<String>scopes,String reason){

public PermissionDecision{scopes=CliContractCollections.copyList(scopes);}}
