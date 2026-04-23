package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.context.layer.TokenEstimator;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.ContextTokenEstimator;

import java.util.Objects;

/**
 * Resolves conversation projection budgets from the same model-aware compaction
 * policy used by LLM preflight.
 */
public class DefaultContextBudgetResolver implements ContextBudgetResolver {

    private static final double CONVERSATION_RATIO = 0.45d;
    private static final double TOOL_RESULT_RATIO = 0.10d;
    private static final double SAFETY_SLACK_RATIO = 0.05d;
    private static final int MIN_CONVERSATION_TOKENS = 512;
    private static final int MIN_TOOL_RESULT_TOKENS = 256;
    private static final int MIN_SAFETY_SLACK_TOKENS = 64;

    private final ContextCompactionPolicy compactionPolicy;
    private final ContextTokenEstimator tokenEstimator;

    public DefaultContextBudgetResolver(ContextCompactionPolicy compactionPolicy) {
        this(compactionPolicy, new ContextTokenEstimator());
    }

    public DefaultContextBudgetResolver(ContextCompactionPolicy compactionPolicy,
            ContextTokenEstimator tokenEstimator) {
        this.compactionPolicy = Objects.requireNonNull(compactionPolicy, "compactionPolicy");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    @Override
    public ContextBudget resolve(AgentContext context, String targetModel) {
        int inputBudget = compactionPolicy.resolveFullRequestThreshold(context);
        if (inputBudget == Integer.MAX_VALUE) {
            return ContextBudget.unlimited();
        }
        int systemPromptBudget = compactionPolicy.resolveSystemPromptThreshold(context);
        int actualSystemPromptTokens = TokenEstimator.estimate(context != null ? context.getSystemPrompt() : null);
        int toolSchemaTokens = tokenEstimator.estimateTools(context != null ? context.getAvailableTools() : null);
        int providerOverheadTokens = tokenEstimator.requestBaseOverheadTokens();
        int safetySlackTokens = Math.max(MIN_SAFETY_SLACK_TOKENS,
                (int) Math.floor(inputBudget * SAFETY_SLACK_RATIO));
        // Detached toolResults are internal loop state, not provider-serialized
        // payload. Keep this aligned with ContextTokenEstimator.estimateRequest.
        int availableForConversation = inputBudget
                - actualSystemPromptTokens
                - toolSchemaTokens
                - providerOverheadTokens
                - safetySlackTokens;

        int proportionalConversation = (int) Math.floor(inputBudget * CONVERSATION_RATIO);
        int conversationBudget = resolveConversationBudget(availableForConversation, proportionalConversation);
        int toolResultBudget = Math.max(MIN_TOOL_RESULT_TOKENS,
                (int) Math.floor(inputBudget * TOOL_RESULT_RATIO));
        return new ContextBudget(inputBudget, systemPromptBudget, conversationBudget, toolResultBudget);
    }

    private int resolveConversationBudget(int availableForConversation, int proportionalConversation) {
        if (availableForConversation < MIN_CONVERSATION_TOKENS) {
            return Math.max(1, availableForConversation);
        }
        return Math.clamp(proportionalConversation, MIN_CONVERSATION_TOKENS, availableForConversation);
    }
}
