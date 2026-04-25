package me.golemcore.bot.domain.context;

/**
 * Prompt-budget behavior for a rendered context layer.
 */
public enum LayerCriticality {
    /**
     * Must be included intact. If it cannot fit, prompt assembly must fail fast.
     */
    PINNED_UNTRIMMABLE,

    /**
     * Must be included, but may be truncated when the hard prompt budget is tight.
     */
    REQUIRED_COMPRESSIBLE,

    /**
     * May be dropped or truncated under prompt-budget pressure.
     */
    OPTIONAL
}
