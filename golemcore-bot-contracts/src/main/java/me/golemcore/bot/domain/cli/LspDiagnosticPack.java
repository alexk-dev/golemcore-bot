package me.golemcore.bot.domain.cli;

import java.util.List;

/**
 * Language-server diagnostics captured for a workspace.
 */
public record LspDiagnosticPack(String language,String file,List<Diagnostic>diagnostics,String version){

public LspDiagnosticPack{diagnostics=CliContractCollections.copyList(diagnostics);}

public record Diagnostic(String path,int line,int column,CliEventSeverity severity,String code,String message){}}
