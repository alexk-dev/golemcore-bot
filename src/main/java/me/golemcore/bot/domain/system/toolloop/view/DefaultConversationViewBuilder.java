package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default request-time conversation view builder.
 *
 * <p>
 * Currently applies model-switch tool masking rules. Later can be extended to
 * be provider-aware (Anthropic/OpenAI formats) without changing ToolLoop logic.
 */
public class DefaultConversationViewBuilder implements ConversationViewBuilder {

    private final ToolMessageMasker toolMessageMasker;

    public DefaultConversationViewBuilder(ToolMessageMasker toolMessageMasker) {
        this.toolMessageMasker = toolMessageMasker;
    }

    @Override
    public ConversationView buildView(AgentContext context, String targetModel) {
        List<Message> messages = context.getMessages() != null ? context.getMessages() : List.of();
        if (context.getSession() == null) {
            return ConversationView.ofMessages(messages);
        }

        String previousModel = readPreviousModel(context);
        boolean needsMasking = needsMasking(previousModel, targetModel, messages);

        if (!needsMasking) {
            return ConversationView.ofMessages(messages);
        }

        ToolMessageMasker.MaskingResult masked = toolMessageMasker.maskToolMessages(messages);
        return new ConversationView(new ArrayList<>(masked.messages()), masked.diagnostics());
    }

    private String readPreviousModel(AgentContext context) {
        Map<String, Object> metadata = context.getSession().getMetadata();
        if (metadata == null) {
            return null;
        }
        return (String) metadata.get(ContextAttributes.LLM_MODEL);
    }

    private boolean needsMasking(String previousModel, String targetModel, List<Message> messages) {
        if (previousModel != null && targetModel != null && !previousModel.equals(targetModel)) {
            return true;
        }
        return previousModel == null && hasToolMessages(messages);
    }

    private boolean hasToolMessages(List<Message> messages) {
        return messages != null && messages.stream().anyMatch(m -> m.hasToolCalls() || m.isToolMessage());
    }
}
