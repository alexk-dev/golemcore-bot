package me.golemcore.bot.adapter.inbound.command;

import java.util.List;

public record ParsedSlashCommand(String command,List<String>args,String rawInput){}
