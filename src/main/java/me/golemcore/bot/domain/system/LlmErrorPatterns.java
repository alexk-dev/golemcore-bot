package me.golemcore.bot.domain.system;

import java.util.Locale;

/**
 * Shared substring markers used across LLM error classification paths.
 *
 * <p>
 * Kept in one place because adapter-level retry decisions and the canonical
 * {@link LlmErrorClassifier} must agree on what counts as a context-overflow
 * error - otherwise the two code paths can diverge provider by provider.
 * </p>
 */
public final class LlmErrorPatterns {

    private LlmErrorPatterns() {
    }

    /**
     * Returns {@code true} when the message looks like a provider context-window /
     * input-too-long error.
     *
     * <p>
     * Accepts a raw (unnormalized) message; null/blank safe.
     * </p>
     */
    public static boolean isContextOverflow(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return matchesContextOverflow(message.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns {@code true} when the already-normalized (lowercased, Locale.ROOT)
     * message looks like a context-overflow error.
     *
     * <p>
     * Markers below are derived from real provider messages propagated by
     * langchain4j 1.x (which does not expose a typed ContextWindowExceeded
     * exception, so string matching is the only portable signal):
     * </p>
     *
     * <ul>
     * <li>OpenAI: "This model's maximum context length is X tokens..." / code
     * "context_length_exceeded"</li>
     * <li>Anthropic: "prompt is too long: X tokens &gt; Y maximum" / "input is too
     * long" / "...exceed context limit"</li>
     * <li>Gemini: "The input token count (X) exceeds the maximum number of tokens
     * allowed"</li>
     * <li>Groq / Together: "Please reduce the length of the messages or completion"
     * / "Request too large"</li>
     * </ul>
     */
    public static boolean matchesContextOverflow(String normalizedMessage) {
        if (normalizedMessage == null || normalizedMessage.isEmpty()) {
            return false;
        }
        return normalizedMessage.contains("context length")
                || normalizedMessage.contains("context_length")
                || normalizedMessage.contains("context window")
                || normalizedMessage.contains("context_window")
                || normalizedMessage.contains("context limit")
                || normalizedMessage.contains("maximum context")
                || normalizedMessage.contains("input token count")
                || normalizedMessage.contains("input is too long")
                || normalizedMessage.contains("prompt is too long")
                || normalizedMessage.contains("reduce the length")
                || normalizedMessage.contains("request too large");
    }
}
