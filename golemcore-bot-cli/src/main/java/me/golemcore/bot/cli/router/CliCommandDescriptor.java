package me.golemcore.bot.cli.router;

import java.util.List;

public record CliCommandDescriptor(String name,List<String>aliases,String description,Class<?>commandClass){

public CliCommandDescriptor{aliases=List.copyOf(aliases);}

public CliCommandDescriptor(String name,String description,Class<?>commandClass){this(name,List.of(),description,commandClass);}}
