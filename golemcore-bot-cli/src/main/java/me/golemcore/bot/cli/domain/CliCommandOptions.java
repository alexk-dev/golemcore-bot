package me.golemcore.bot.cli.domain;

import java.nio.file.Path;
import java.util.Objects;

public record CliCommandOptions(ProjectOptions projectOptions,RuntimeSelectionOptions runtimeSelection,OutputOptions output,TraceOptions traceOptions,CapabilityOptions capabilities,PermissionOptions permissions,BudgetOptions budget,AttachOptions attachOptions){

public CliCommandOptions{Objects.requireNonNull(projectOptions,"projectOptions");Objects.requireNonNull(runtimeSelection,"runtimeSelection");Objects.requireNonNull(output,"output");Objects.requireNonNull(traceOptions,"traceOptions");Objects.requireNonNull(capabilities,"capabilities");Objects.requireNonNull(permissions,"permissions");Objects.requireNonNull(budget,"budget");Objects.requireNonNull(attachOptions,"attachOptions");}

public Path cwd(){return projectOptions.cwd();}

public Path project(){return projectOptions.project();}

public Path workspace(){return projectOptions.workspace();}

public Path config(){return projectOptions.config();}

public Path configDir(){return projectOptions.configDir();}

public String profile(){return projectOptions.profile();}

public Path envFile(){return projectOptions.envFile();}

public String model(){return runtimeSelection.model();}

public String tier(){return runtimeSelection.tier();}

public String agent(){return runtimeSelection.agent();}

public String session(){return runtimeSelection.session();}

public boolean continueSession(){return runtimeSelection.continueSession();}

public String fork(){return runtimeSelection.fork();}

public String format(){return output.format();}

public boolean json(){return output.json();}

public boolean noColor(){return output.noColor();}

public String color(){return output.color();}

public boolean quiet(){return output.quiet();}

public boolean verbose(){return output.verbose();}

public String logLevel(){return output.logLevel();}

public boolean trace(){return traceOptions.trace();}

public Path traceExport(){return traceOptions.traceExport();}

public boolean noMemory(){return capabilities.noMemory();}

public boolean noRag(){return capabilities.noRag();}

public boolean noMcp(){return capabilities.noMcp();}

public boolean noSkills(){return capabilities.noSkills();}

public String permissionMode(){return permissions.permissionMode();}

public boolean yes(){return permissions.yes();}

public boolean noInput(){return permissions.noInput();}

public String timeout(){return budget.timeout();}

public Integer maxLlmCalls(){return budget.maxLlmCalls();}

public Integer maxToolExecutions(){return budget.maxToolExecutions();}

public String attach(){return attachOptions.attach();}

public Integer port(){return attachOptions.port();}

public String hostname(){return attachOptions.hostname();}

public record ProjectOptions(Path cwd,Path project,Path workspace,Path config,Path configDir,String profile,Path envFile){}

public record RuntimeSelectionOptions(String model,String tier,String agent,String session,boolean continueSession,String fork){}

public record OutputOptions(String format,boolean json,boolean noColor,String color,boolean quiet,boolean verbose,String logLevel){}

public record TraceOptions(boolean trace,Path traceExport){}

public record CapabilityOptions(boolean noMemory,boolean noRag,boolean noMcp,boolean noSkills){}

public record PermissionOptions(String permissionMode,boolean yes,boolean noInput){}

public record BudgetOptions(String timeout,Integer maxLlmCalls,Integer maxToolExecutions){}

public record AttachOptions(String attach,Integer port,String hostname){}}
