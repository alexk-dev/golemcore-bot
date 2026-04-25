package me.golemcore.bot.security;

import me.golemcore.bot.security.ContentPolicy.PolicyCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPolicyTest {

    private static final String VIOLATION_SENSITIVE_DATA = "Content may contain sensitive data";

    private ContentPolicy contentPolicy;

    @BeforeEach
    void setUp() {
        contentPolicy = new ContentPolicy();
    }

    // ==================== checkContent() ====================

    @Test
    void shouldReturnOkWhenContentIsNull() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent(null);

        // Assert
        assertTrue(result.isOk());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void shouldReturnOkWhenContentIsBlank() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("   ");

        // Assert
        assertTrue(result.isOk());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void shouldReturnOkWhenContentIsEmpty() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("");

        // Assert
        assertTrue(result.isOk());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void shouldReturnViolationWhenContentExceedsMaxLength() {
        // Arrange
        String longContent = "a".repeat(10001);

        // Act
        PolicyCheckResult result = contentPolicy.checkContent(longContent);

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains("Content exceeds maximum length"));
    }

    @Test
    void shouldReturnOkWhenContentIsExactlyMaxLength() {
        // Arrange
        String exactContent = "a".repeat(10000);

        // Act
        PolicyCheckResult result = contentPolicy.checkContent(exactContent);

        // Assert
        assertTrue(result.isOk());
    }

    @Test
    void shouldReturnViolationWhenContentContainsPassword() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("password = 'mysecretpass123'");

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
    }

    @Test
    void shouldReturnViolationWhenContentContainsApiKey() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("api_key: \"sk-abc123def456\"");

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
    }

    @Test
    void shouldReturnViolationWhenContentContainsApiKeyWithHyphen() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("api-key = \"mykey12345\"");

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
    }

    @Test
    void shouldReturnViolationWhenContentContainsSecret() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("secret: \"topsecret\"");

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
    }

    @Test
    void shouldReturnViolationWhenContentContainsBearerToken() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token");

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
    }

    @Test
    void shouldReturnViolationWhenContentContainsEmailAddress() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("Contact me at john.doe@example.com for details");

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
    }

    @Test
    void shouldReturnOkWhenContentIsClean() {
        // Act
        PolicyCheckResult result = contentPolicy.checkContent("Hello, this is a normal safe message.");

        // Assert
        assertTrue(result.isOk());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void shouldReturnMultipleViolationsWhenContentIsTooLongAndContainsSensitiveData() {
        // Arrange
        String longSensitiveContent = "password = 'secret' " + "a".repeat(10000);

        // Act
        PolicyCheckResult result = contentPolicy.checkContent(longSensitiveContent);

        // Assert
        assertFalse(result.isOk());
        assertTrue(result.violations().contains("Content exceeds maximum length"));
        assertTrue(result.violations().contains(VIOLATION_SENSITIVE_DATA));
        assertEquals(2, result.violations().size());
    }

    // ==================== redactSensitive() ====================

    @Test
    void shouldRedactPasswords() {
        // Arrange
        String content = "password = 'mysecret123'";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertEquals("password = '[REDACTED]'", result);
    }

    @Test
    void shouldRedactPasswordsWithDoubleQuotes() {
        // Arrange
        String content = "password: \"topsecret\"";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertEquals("password: \"[REDACTED]\"", result);
    }

    @Test
    void shouldRedactApiKeys() {
        // Arrange
        String content = "api_key = 'sk-abc123def456'";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertEquals("api_key = '[REDACTED]'", result);
    }

    @Test
    void shouldRedactApiKeysWithHyphen() {
        // Arrange
        String content = "api-key: \"mykey12345\"";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertEquals("api-key: \"[REDACTED]\"", result);
    }

    @Test
    void shouldRedactBearerTokens() {
        // Arrange
        String content = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertEquals("Authorization: Bearer [REDACTED]", result);
    }

    @Test
    void shouldReturnEmptyStringWhenInputIsNull() {
        // Act
        String result = contentPolicy.redactSensitive(null);

        // Assert
        assertEquals("", result);
    }

    @Test
    void shouldReturnOriginalContentWhenNothingToRedact() {
        // Arrange
        String content = "This is a clean message with no secrets.";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertEquals(content, result);
    }

    @Test
    void shouldRedactMultipleSensitivePatterns() {
        // Arrange
        String content = "password = 'pass1' and Bearer abc123token";

        // Act
        String result = contentPolicy.redactSensitive(content);

        // Assert
        assertTrue(result.contains("[REDACTED]"));
        assertFalse(result.contains("pass1"));
        assertFalse(result.contains("abc123token"));
    }

    // ==================== truncate() ====================

    @Test
    void shouldReturnEmptyStringWhenTruncatingNull() {
        // Act
        String result = contentPolicy.truncate(null, 100);

        // Assert
        assertEquals("", result);
    }

    @Test
    void shouldNotTruncateWhenContentIsWithinLimit() {
        // Arrange
        String content = "Short text";

        // Act
        String result = contentPolicy.truncate(content, 100);

        // Assert
        assertEquals("Short text", result);
    }

    @Test
    void shouldNotTruncateWhenContentIsExactlyMaxLength() {
        // Arrange
        String content = "abcde";

        // Act
        String result = contentPolicy.truncate(content, 5);

        // Assert
        assertEquals("abcde", result);
    }

    @Test
    void shouldTruncateAndAppendEllipsisWhenContentExceedsMaxLength() {
        // Arrange
        String content = "This is a long piece of content that should be truncated";

        // Act
        String result = contentPolicy.truncate(content, 20);

        // Assert
        assertEquals(20, result.length());
        assertTrue(result.endsWith("..."));
        assertEquals("This is a long pi...", result);
    }

    // ==================== PolicyCheckResult ====================

    @Test
    void shouldCreateOkPolicyCheckResult() {
        // Act
        PolicyCheckResult result = PolicyCheckResult.ok();

        // Assert
        assertTrue(result.isOk());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void shouldCreateViolationPolicyCheckResult() {
        // Arrange
        java.util.List<String> violations = java.util.List.of("violation1", "violation2");

        // Act
        PolicyCheckResult result = PolicyCheckResult.violation(violations);

        // Assert
        assertFalse(result.isOk());
        assertEquals(2, result.violations().size());
        assertTrue(result.violations().contains("violation1"));
        assertTrue(result.violations().contains("violation2"));
    }
}
