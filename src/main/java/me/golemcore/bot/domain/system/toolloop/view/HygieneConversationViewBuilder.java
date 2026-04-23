package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;

import java.util.Objects;

/**
 * Decorates a provider/model-specific view builder with request-time context
 * hygiene and budgeting.
 */
public final class HygieneConversationViewBuilder implements ConversationViewBuilder {

    private final ConversationViewBuilder delegate;
    private final ContextWindowProjector projector;
    private final ContextBudgetResolver budgetResolver;

    public HygieneConversationViewBuilder(
            ConversationViewBuilder delegate,
            ContextWindowProjector projector,
            ContextBudgetResolver budgetResolver) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.budgetResolver = Objects.requireNonNull(budgetResolver, "budgetResolver");
    }

    @Override
    public ConversationView buildView(AgentContext context, String targetModel) {
        ConversationView base = delegate.buildView(context, targetModel);
        ContextBudget budget = budgetResolver.resolve(context, targetModel);
        return projector.project(context, base, budget);
    }
}
