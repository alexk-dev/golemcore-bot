package me.golemcore.bot.domain.cli;

/**
 * Stable identity for a workspace/project visible to a CLI client.
 */
public record ProjectIdentity(String projectId,String rootPath,String gitRoot,ProjectTrustState trustState,String configPath,java.util.List<String>rulesFiles){

public ProjectIdentity{rulesFiles=CliContractCollections.copyList(rulesFiles);}}
