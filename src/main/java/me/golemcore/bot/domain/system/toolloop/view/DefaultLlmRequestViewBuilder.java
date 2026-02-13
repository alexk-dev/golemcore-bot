package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;

import java.util.List;

/**
 * Backward-compatible adapter for the legacy naming (LlmRequestViewBuilder).
 *
 * <p>
 * New code should prefer {@link ConversationViewBuilder}.
 */
public class DefaultLlmRequestViewBuilder implements LlmRequestViewBuilder {

    private final ConversationViewBuilder conversationViewBuilder;

    public DefaultLlmRequestViewBuilder(ConversationViewBuilder conversationViewBuilder) {
        this.conversationViewBuilder = conversationViewBuilder;
    }

    public DefaultLlmRequestViewBuilder(ToolMessageMasker toolMessageMasker) {
        this(new DefaultConversationViewBuilder(toolMessageMasker));
    }

    @Override
    public List<Message> buildMessagesView(AgentContext context, String targetModel) {
        return conversationViewBuilder.buildView(context, targetModel).messages();
    }
}
