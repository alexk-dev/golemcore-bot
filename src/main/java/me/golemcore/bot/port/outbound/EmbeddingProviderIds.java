package me.golemcore.bot.port.outbound;

/**
 * Provider identifiers understood by the embedding resolver port.
 */
public final class EmbeddingProviderIds {

    public static final String OLLAMA = "ollama";
    public static final String OPENAI = "openai";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String OPENAI_COMPATIBLE_UNDERSCORE = "openai_compatible";

    private EmbeddingProviderIds() {
    }
}
