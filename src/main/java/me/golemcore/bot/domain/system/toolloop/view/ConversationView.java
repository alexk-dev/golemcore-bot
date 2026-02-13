package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Request-time projection of conversation history.
 *
 * <p>
 * Raw history must never be mutated. Any masking/normalization for a target LLM
 * must be represented as a view.
 */
public record ConversationView(List<Message>messages,List<String>diagnostics){

public static ConversationView ofMessages(List<Message>messages){return new ConversationView(new ArrayList<>(messages),List.of());}

public ConversationView{messages=messages!=null?messages:List.of();diagnostics=diagnostics!=null?diagnostics:List.of();}}
