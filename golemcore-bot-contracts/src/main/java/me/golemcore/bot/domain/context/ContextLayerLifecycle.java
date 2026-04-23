package me.golemcore.bot.domain.context;

/**
 * Describes how often a context layer is expected to change.
 *
 * <p>
 * The lifecycle is prompt-budget metadata: it does not decide whether a layer
 * applies, but it gives diagnostics and future selectors a stable way to
 * distinguish static instructions from turn-specific retrieval.
 */
public enum ContextLayerLifecycle {
    STATIC, SESSION, TURN, ON_DEMAND
}
