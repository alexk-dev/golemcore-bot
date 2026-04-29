package me.golemcore.bot.domain.cli;

import java.util.List;
import java.util.Map;

/**
 * Tool permission request presented to a CLI client.
 */
public record PermissionRequest(String requestId,String runId,String tool,String argsSummary,PermissionRisk risk,Map<String,Object>argsPreview,List<String>scopes,List<String>choices,String diffPreview){

public PermissionRequest{argsPreview=CliContractCollections.copyObjectMap(argsPreview);scopes=CliContractCollections.copyList(scopes);choices=CliContractCollections.copyList(choices);}}
