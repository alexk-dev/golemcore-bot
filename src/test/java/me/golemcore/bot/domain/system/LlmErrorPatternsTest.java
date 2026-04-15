package me.golemcore.bot.domain.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage for {@link LlmErrorPatterns}. {@link LlmErrorClassifierTest}
 * exercises the shared contract through the classifier, but each marker must
 * also be reachable via the raw entry point so adapter-level retry paths that
 * call {@link LlmErrorPatterns#isContextOverflow(String)} directly stay in sync
 * with the classifier.
 */
class LlmErrorPatternsTest {

    @Test
    void isContextOverflow_shouldReturnFalseForNull() {
        assertFalse(LlmErrorPatterns.isContextOverflow(null),
                "null input must short-circuit before any substring scan runs");
    }

    @Test
    void isContextOverflow_shouldReturnFalseForBlank() {
        assertFalse(LlmErrorPatterns.isContextOverflow("   "));
    }

    @Test
    void isContextOverflow_shouldNormalizeCasingBeforeMatching() {
        assertTrue(LlmErrorPatterns.isContextOverflow("THIS MODEL'S MAXIMUM CONTEXT LENGTH IS 128000 TOKENS"),
                "raw-input entry must lowercase before matching - providers emit mixed case");
    }

    @Test
    void isContextOverflow_shouldReturnFalseForUnrelatedProviderError() {
        assertFalse(LlmErrorPatterns.isContextOverflow("503 Service Unavailable: upstream is down"));
    }

    @Test
    void matchesContextOverflow_shouldReturnFalseForNull() {
        assertFalse(LlmErrorPatterns.matchesContextOverflow(null));
    }

    @Test
    void matchesContextOverflow_shouldReturnFalseForEmpty() {
        assertFalse(LlmErrorPatterns.matchesContextOverflow(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Every marker in LlmErrorPatterns#matchesContextOverflow must be
            // hit by at least one value here - adding a new marker without an
            // accompanying value drops branch coverage below 100% for this class.
            "this model's maximum context length is 128000 tokens",
            "code: context_length_exceeded",
            "you exceeded the context window for gpt-4",
            "field context_window overflow",
            "input length and max_tokens exceed context limit",
            "exceeds maximum context size for this model",
            "the input token count (200000) exceeds the maximum",
            "input is too long for requested model",
            "prompt is too long: 200000 tokens > 128000 maximum",
            "please reduce the length of the messages or completion",
            "request too large for gpt-4-turbo"
    })
    void matchesContextOverflow_shouldMatchEveryKnownMarker(String normalizedMessage) {
        assertTrue(LlmErrorPatterns.matchesContextOverflow(normalizedMessage),
                "marker must be recognized - if this fails, context-overflow recovery won't fire for that provider");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rate limit reached for gpt-4",
            "invalid api key",
            "upstream connection reset",
            "model not found"
    })
    void matchesContextOverflow_shouldRejectUnrelatedProviderErrors(String normalizedMessage) {
        assertFalse(LlmErrorPatterns.matchesContextOverflow(normalizedMessage),
                "non-overflow provider errors must not be misclassified as context overflow");
    }
}
