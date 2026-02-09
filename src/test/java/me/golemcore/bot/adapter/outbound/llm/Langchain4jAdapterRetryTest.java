package me.golemcore.bot.adapter.outbound.llm;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for rate limit detection logic in Langchain4jAdapter.
 */
class Langchain4jAdapterRetryTest {

    private static final String IS_RATE_LIMIT_ERROR = "isRateLimitError";
    private static final String SANITIZE_FUNCTION_NAME = "sanitizeFunctionName";

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

    // ===== sanitizeFunctionName =====

    @Test
    void sanitizeFunctionName_validName() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, "my_tool");
        assertEquals("my_tool", result);
    }

    @Test
    void sanitizeFunctionName_validNameWithDash() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, "my-tool");
        assertEquals("my-tool", result);
    }

    @Test
    void sanitizeFunctionName_replacesDotsWithUnderscore() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, "tools.read_file");
        assertEquals("tools_read_file", result);
    }

    @Test
    void sanitizeFunctionName_replacesSpacesAndSpecialChars() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, "my tool/v2!");
        assertEquals("my_tool_v2_", result);
    }

    @Test
    void sanitizeFunctionName_nullReturnsUnknown() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, (String) null);
        assertEquals("unknown", result);
    }

    @Test
    void sanitizeFunctionName_allInvalidCharsReplacedWithUnderscore() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        // Dots -> underscores, result is "___" (not empty)
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, "...");
        assertEquals("___", result);
    }

    @Test
    void sanitizeFunctionName_alreadyValid() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME, "filesystem_read");
        assertEquals("filesystem_read", result);
    }

    @Test
    void sanitizeFunctionName_unicodeReplaced() {
        Langchain4jAdapter adapter = createMinimalAdapter();
        String result = ReflectionTestUtils.invokeMethod(adapter, SANITIZE_FUNCTION_NAME,
                "tool_\u0444\u044B\u0432\u0430");
        assertTrue(result.matches("^[a-zA-Z0-9_-]+$"));
    }

    private Langchain4jAdapter createMinimalAdapter() {
        // Create with nulls -- only testing isRateLimitError/sanitize which don't use
        // fields
        return new Langchain4jAdapter(null, null);
    }
}
