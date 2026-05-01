package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Process-level CLI invocation metadata.
 */
public record CliInvocation(String executable,List<String>arguments,Map<String,String>environment,String workingDirectory,@JsonFormat(shape=JsonFormat.Shape.STRING)Instant startedAt){

public CliInvocation{arguments=CliContractCollections.copyList(arguments);environment=CliContractCollections.copyStringMap(environment);}}
