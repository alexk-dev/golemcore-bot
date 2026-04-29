package me.golemcore.bot.domain.cli;

import java.util.List;

/**
 * Proposed patch content and affected files for a CLI client.
 */
public record PatchSet(String patchId,List<String>files,List<String>hunks,String authorRunId,PatchStatus status){

public PatchSet{files=CliContractCollections.copyList(files);hunks=CliContractCollections.copyList(hunks);}}
