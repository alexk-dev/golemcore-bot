package me.golemcore.bot.adapter.outbound.llm;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for rate limit detection logic in Langchain4jAdapter.
 */
class Langchain4jAdapterRetryTest {

    private static final String IS_RATE_LIMIT_ERROR = "isRateLimitError";

    @Test
    void isRateLimitError_detectsTokenQuotaExceeded() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException ex = new RuntimeException("LLM chat failed: {\"message\":\"Tokens per minute limit exceeded\","
                + "\"type\":\"too_many_tokens_error\",\"code\":\"token_quota_exceeded\"}");

        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    @Test
    void isRateLimitError_detectsRateLimitInMessage() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException ex = new RuntimeException("rate_limit_exceeded");
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    @Test
    void isRateLimitError_detects429() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException ex = new RuntimeException("HTTP 429 Too Many Requests");
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    @Test
    void isRateLimitError_detectsTooManyRequests() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException ex = new RuntimeException("Too Many Requests");
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    @Test
    void isRateLimitError_detectsNestedCause() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException cause = new RuntimeException("token_quota_exceeded");
        RuntimeException ex = new RuntimeException("LLM failed", cause);

        assertTrue((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    @Test
    void isRateLimitError_returnsFalseForOtherErrors() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException ex = new RuntimeException("Connection refused");
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    @Test
    void isRateLimitError_returnsFalseForNullMessage() {
        Langchain4jAdapter adapter = createMinimalAdapter();

        RuntimeException ex = new RuntimeException((String) null);
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(adapter, IS_RATE_LIMIT_ERROR, ex));
    }

    private Langchain4jAdapter createMinimalAdapter() {
        // Create with nulls -- only testing isRateLimitError/sanitize which don't use
        // fields
        return new Langchain4jAdapter(null, null, null);
    }
}
