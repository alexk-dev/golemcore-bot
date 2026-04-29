package me.golemcore.bot.domain.cli;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of workspace state visible to a CLI run.
 */
public record WorkspaceSnapshot(String snapshotId,String gitRef,Map<String,String>fileHashes,List<String>patchRefs){

public WorkspaceSnapshot{fileHashes=CliContractCollections.copyStringMap(fileHashes);patchRefs=CliContractCollections.copyList(patchRefs);}}
