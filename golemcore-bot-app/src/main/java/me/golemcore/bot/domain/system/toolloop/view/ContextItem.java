package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.List;

record ContextItem(int ordinal,ContextItemKind kind,List<Message>messages){

ContextItem{messages=messages==null?List.of():List.copyOf(messages);}

Message first(){return messages.isEmpty()?null:messages.get(0);}}
