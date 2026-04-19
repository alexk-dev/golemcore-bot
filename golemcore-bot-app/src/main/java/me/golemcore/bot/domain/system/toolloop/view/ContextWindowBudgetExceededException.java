package me.golemcore.bot.domain.system.toolloop.view;

/**
 * Raised before a provider call when even the minimal protocol-safe
 * conversation projection cannot fit the resolved context budget.
 */
public class ContextWindowBudgetExceededException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ContextWindowBudgetExceededException(int budgetTokens, int projectedTokens) {
        super("Minimal conversation context exceeds budget: projectedTokens="
                + projectedTokens + ", budgetTokens=" + budgetTokens);
    }
}
