package me.golemcore.bot.domain.cli;

import java.util.List;
import java.util.Map;

/**
 * Channel-neutral coding-agent profile loaded from project or user storage.
 */
public record AgentProfile(String id,String name,String description,String mode,String model,String tier,List<String>skills,List<String>mcp,Map<String,String>tools,Map<String,Object>permissions,boolean memoryRead,boolean memoryWrite,boolean ragRead,String defaultOutputFormat,String prompt){

public AgentProfile{skills=CliContractCollections.copyList(skills);mcp=CliContractCollections.copyList(mcp);tools=CliContractCollections.copyStringMap(tools);permissions=CliContractCollections.copyObjectMap(permissions);}}
