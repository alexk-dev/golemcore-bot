package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.List;

record ProjectedItem(ContextItem item,List<Message>messages,boolean pinned,int score,int estimatedTokens){

ProjectedItem{messages=messages==null?List.of():List.copyOf(messages);}}
