package me.golemcore.bot.adapter.outbound.llm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for rate limit detection logic in Langchain4jAdapter.
 */
class Langchain4jAdapterRetryTest {

    @Test
    void isRateLimitError_detectsTokenQuotaExceeded() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var ex = new RuntimeException("LLM chat failed: {\"message\":\"Tokens per minute limit exceeded\","
                + "\"type\":\"too_many_tokens_error\",\"code\":\"token_quota_exceeded\"}");

        assertTrue((boolean) method.invoke(adapter, ex));
    }

    @Test
    void isRateLimitError_detectsRateLimitInMessage() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var ex = new RuntimeException("rate_limit_exceeded");
        assertTrue((boolean) method.invoke(adapter, ex));
    }

    @Test
    void isRateLimitError_detects429() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var ex = new RuntimeException("HTTP 429 Too Many Requests");
        assertTrue((boolean) method.invoke(adapter, ex));
    }

    @Test
    void isRateLimitError_detectsTooManyRequests() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var ex = new RuntimeException("Too Many Requests");
        assertTrue((boolean) method.invoke(adapter, ex));
    }

    @Test
    void isRateLimitError_detectsNestedCause() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var cause = new RuntimeException("token_quota_exceeded");
        var ex = new RuntimeException("LLM failed", cause);

        assertTrue((boolean) method.invoke(adapter, ex));
    }

    @Test
    void isRateLimitError_returnsFalseForOtherErrors() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var ex = new RuntimeException("Connection refused");
        assertFalse((boolean) method.invoke(adapter, ex));
    }

    @Test
    void isRateLimitError_returnsFalseForNullMessage() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getIsRateLimitErrorMethod();

        var ex = new RuntimeException((String) null);
        assertFalse((boolean) method.invoke(adapter, ex));
    }

    // ===== sanitizeFunctionName =====

    @Test
    void sanitizeFunctionName_validName() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        assertEquals("my_tool", method.invoke(adapter, "my_tool"));
    }

    @Test
    void sanitizeFunctionName_validNameWithDash() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        assertEquals("my-tool", method.invoke(adapter, "my-tool"));
    }

    @Test
    void sanitizeFunctionName_replacesDotsWithUnderscore() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        assertEquals("tools_read_file", method.invoke(adapter, "tools.read_file"));
    }

    @Test
    void sanitizeFunctionName_replacesSpacesAndSpecialChars() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        assertEquals("my_tool_v2_", method.invoke(adapter, "my tool/v2!"));
    }

    @Test
    void sanitizeFunctionName_nullReturnsUnknown() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        assertEquals("unknown", method.invoke(adapter, (String) null));
    }

    @Test
    void sanitizeFunctionName_allInvalidCharsReplacedWithUnderscore() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        // Dots → underscores, result is "___" (not empty)
        assertEquals("___", method.invoke(adapter, "..."));
    }

    @Test
    void sanitizeFunctionName_alreadyValid() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        assertEquals("filesystem_read", method.invoke(adapter, "filesystem_read"));
    }

    @Test
    void sanitizeFunctionName_unicodeReplaced() throws Exception {
        var adapter = createMinimalAdapter();
        var method = getSanitizeFunctionNameMethod();
        String result = (String) method.invoke(adapter, "tool_фыва");
        assertTrue(result.matches("^[a-zA-Z0-9_-]+$"));
    }

    private Langchain4jAdapter createMinimalAdapter() {
        // Create with nulls — only testing isRateLimitError/sanitize which don't use
        // fields
        return new Langchain4jAdapter(null, null);
    }

    private Method getIsRateLimitErrorMethod() throws NoSuchMethodException {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("isRateLimitError", Throwable.class);
        method.setAccessible(true);
        return method;
    }

    private Method getSanitizeFunctionNameMethod() throws NoSuchMethodException {
        Method method = Langchain4jAdapter.class.getDeclaredMethod("sanitizeFunctionName", String.class);
        method.setAccessible(true);
        return method;
    }
}
