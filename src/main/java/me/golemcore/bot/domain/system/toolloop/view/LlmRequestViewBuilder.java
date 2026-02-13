package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;

import java.util.List;

/**
 * Builds a provider-safe, request-time view of the conversation.
 *
 * <p>
 * Important: this builder must never mutate raw history stored in
 * {@link me.golemcore.bot.domain.model.AgentSession}.
 */
public interface LlmRequestViewBuilder {

    List<Message> buildMessagesView(AgentContext context, String targetModel);
}
