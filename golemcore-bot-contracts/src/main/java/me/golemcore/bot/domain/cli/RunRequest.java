package me.golemcore.bot.domain.cli;

import java.util.List;
import java.util.Map;

/**
 * Request to start an agent run from a CLI client.
 */
public record RunRequest(String requestId,String prompt,List<String>files,List<String>contextRefs,String sessionId,String agentId,String tier,String model,RunBudget budget,CliPermissionMode permissionMode,CliOutputFormat outputFormat,Map<String,String>metadata){

public RunRequest{files=CliContractCollections.copyList(files);contextRefs=CliContractCollections.copyList(contextRefs);metadata=CliContractCollections.copyStringMap(metadata);}}
