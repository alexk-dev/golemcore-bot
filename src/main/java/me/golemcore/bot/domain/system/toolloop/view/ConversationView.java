package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.List;

/**
 * Request-time projection of conversation history.
 *
 * <p>
 * Raw history must never be mutated. Any masking/normalization for a target LLM
 * must be represented as a view.
 */
public record ConversationView(List<Message>messages,List<String>diagnostics){

public ConversationView{messages=messages==null?List.of():List.copyOf(messages);diagnostics=diagnostics==null?List.of():List.copyOf(diagnostics);}

public static ConversationView ofMessages(List<Message>messages){return new ConversationView(messages,List.of());}}
