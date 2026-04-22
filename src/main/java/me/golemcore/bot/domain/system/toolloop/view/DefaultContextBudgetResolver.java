package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.context.layer.TokenEstimator;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;

import java.util.Objects;

/**
 * Resolves conversation projection budgets from the same model-aware compaction
 * policy used by LLM preflight.
 */
public class DefaultContextBudgetResolver implements ContextBudgetResolver {

    private static final double CONVERSATION_RATIO = 0.45d;
    private static final double TOOL_RESULT_RATIO = 0.10d;
    private static final int MIN_CONVERSATION_TOKENS = 512;
    private static final int MIN_TOOL_RESULT_TOKENS = 256;

    private final ContextCompactionPolicy compactionPolicy;

    public DefaultContextBudgetResolver(ContextCompactionPolicy compactionPolicy) {
        this.compactionPolicy = Objects.requireNonNull(compactionPolicy, "compactionPolicy");
    }

    @Override
    public ContextBudget resolve(AgentContext context, String targetModel) {
        int inputBudget = compactionPolicy.resolveFullRequestThreshold(context);
        if (inputBudget == Integer.MAX_VALUE) {
            return ContextBudget.unlimited();
        }
        int systemPromptBudget = compactionPolicy.resolveSystemPromptThreshold(context);
        int actualSystemPromptTokens = TokenEstimator.estimate(context != null ? context.getSystemPrompt() : null);
        int reservedSystemTokens = Math.min(systemPromptBudget, actualSystemPromptTokens);
        int remainingAfterSystem = Math.max(1, inputBudget - reservedSystemTokens);
        int proportionalConversation = (int) Math.floor(inputBudget * CONVERSATION_RATIO);
        int conversationBudget = Math.max(MIN_CONVERSATION_TOKENS,
                Math.min(remainingAfterSystem, proportionalConversation));
        int toolResultBudget = Math.max(MIN_TOOL_RESULT_TOKENS,
                (int) Math.floor(inputBudget * TOOL_RESULT_RATIO));
        return new ContextBudget(inputBudget, systemPromptBudget, conversationBudget, toolResultBudget);
    }
}
