package me.golemcore.bot.cli;

import java.nio.file.Path;
import java.util.List;

public record CliOptions(Path cwd,Path project,Path workspace,Path config,Path configDir,String profile,Path envFile,String model,String tier,String agent,String session,boolean continueSession,String fork,String format,boolean json,boolean noColor,String color,boolean quiet,boolean verbose,String logLevel,boolean trace,Path traceExport,boolean noMemory,boolean noRag,boolean noMcp,boolean noSkills,String permissionMode,boolean yes,boolean noInput,String timeout,Integer maxLlmCalls,Integer maxToolExecutions,String attach,Integer port,String hostname,List<String>javaOptions){

public CliOptions{javaOptions=List.copyOf(javaOptions);}}
