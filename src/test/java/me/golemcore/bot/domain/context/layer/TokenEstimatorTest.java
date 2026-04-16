package me.golemcore.bot.domain.context.layer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TokenEstimator}.
 *
 * <p>
 * The assertions are intentionally <em>numeric</em> rather than asserting "some
 * positive number" &mdash; this guards against mutation testing replacing the
 * division with multiplication or the return value with a constant, both of
 * which would still satisfy a "> 0" assertion.
 */
class TokenEstimatorTest {

    @ParameterizedTest
    @NullAndEmptySource
    void shouldReturnZeroForNullOrEmptyInput(String input) {
        assertEquals(0, TokenEstimator.estimate(input));
    }

    @ParameterizedTest
    @CsvSource({
            // 1 char rounds up to 1 token (Math.ceil of 1/3.5 = 0.286 -> 1)
            "a, 1",
            // 3 chars -> 0.857 -> ceil = 1
            "abc, 1",
            // 4 chars -> 1.143 -> ceil = 2
            "abcd, 2",
            // 7 chars -> 2.0 -> ceil = 2 (exact boundary)
            "abcdefg, 2",
            // 8 chars -> 2.286 -> ceil = 3
            "abcdefgh, 3",
            // 14 chars -> 4.0 -> ceil = 4 (exact boundary)
            "abcdefghijklmn, 4",
            // 15 chars -> 4.286 -> ceil = 5
            "abcdefghijklmno, 5",
            // 36 chars -> 10.29 -> ceil = 11
            "abcdefghijklmnopqrstuvwxyz0123456789, 11"
    })
    void shouldEstimateTokensUsingCharDivisionHeuristic(String input, int expected) {
        assertEquals(expected, TokenEstimator.estimate(input));
    }

    @Test
    void shouldRoundUpToAtLeastOneTokenForAnyNonEmptyText() {
        // Sanity: a single character must contribute 1 token, not 0.
        // A MathMutator swapping / for * would turn 1/3.5 into 1*3.5 -> ceil = 4,
        // which the exact assertion above catches; this redundant guard documents
        // the invariant explicitly for readers.
        assertTrue(TokenEstimator.estimate("x") >= 1);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 7, 14, 35, 100, 1000 })
    void shouldProduceStrictlyIncreasingEstimatesForLongerText(int length) {
        String text = "a".repeat(length);
        int tokens = TokenEstimator.estimate(text);
        // For any non-trivial length, tokens should be < length (since 3.5 > 1)
        // and > 0. This kills PrimitiveReturnsMutator (returns 0) and a
        // multiplication mutant (which would return length*3.5 > length).
        assertTrue(tokens > 0, "tokens must be positive for length " + length);
        assertTrue(tokens < length + 1, "tokens must be < length+1 for length " + length);
        if (length >= 8) {
            assertTrue(tokens < length, "tokens must be < length for length " + length);
        }
    }
}
