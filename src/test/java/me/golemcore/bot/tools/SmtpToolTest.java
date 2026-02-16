package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("PMD.AvoidDuplicateLiterals") // Test readability over deduplication
class SmtpToolTest {

    private static final String SMTP_HOST = "smtp.example.com";
    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "secret";
    private static final String PARAM_OPERATION = "operation";
    private static final String OP_SEND_EMAIL = "send_email";
    private static final String OP_REPLY_EMAIL = "reply_email";
    private static final String PARAM_TO = "to";
    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_BODY = "body";
    private static final String TEST_RECIPIENT = "test@example.com";

    private RuntimeConfigService runtimeConfigService;
    private BotProperties.SmtpToolProperties smtpConfig;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        smtpConfig = new BotProperties.SmtpToolProperties();
        when(runtimeConfigService.getResolvedSmtpConfig()).thenReturn(smtpConfig);
    }

    // ==================== isEnabled ====================

    @Test
    void shouldBeDisabledByDefault() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoHost() {
        smtpConfig.setEnabled(true);
        smtpConfig.setHost("");
        smtpConfig.setUsername(USERNAME);

        SmtpTool tool = new SmtpTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoUsername() {
        smtpConfig.setEnabled(true);
        smtpConfig.setHost(SMTP_HOST);
        smtpConfig.setUsername("");

        SmtpTool tool = new SmtpTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeEnabledWhenFullyConfigured() {
        configureProperties();

        SmtpTool tool = new SmtpTool(runtimeConfigService);

        assertTrue(tool.isEnabled());
    }

    // ==================== Definition ====================

    @Test
    void shouldReturnCorrectDefinition() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        ToolDefinition definition = tool.getDefinition();

        assertEquals("smtp", definition.getName());
        assertNotNull(definition.getDescription());
        assertNotNull(definition.getInputSchema());
    }

    // ==================== Parameter validation ====================

    @Test
    void shouldFailWithMissingOperation() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_OPERATION));
    }

    @Test
    void shouldFailWithUnknownOperation() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "delete_email")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    @Test
    void shouldFailWithMissingTo() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_SEND_EMAIL,
                PARAM_SUBJECT, "Test",
                PARAM_BODY, "Hello")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_TO));
    }

    @Test
    void shouldFailWithMissingSubject() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_SEND_EMAIL,
                PARAM_TO, TEST_RECIPIENT,
                PARAM_BODY, "Hello")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_SUBJECT));
    }

    @Test
    void shouldFailWithMissingBody() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_SEND_EMAIL,
                PARAM_TO, TEST_RECIPIENT,
                PARAM_SUBJECT, "Test")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_BODY));
    }

    @Test
    void shouldFailWithBlankTo() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, "   ");
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "Hello");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_TO));
    }

    @Test
    void shouldFailWithBlankSubject() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "");
        params.put(PARAM_BODY, "Hello");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_SUBJECT));
    }

    @Test
    void shouldFailWithBlankBody() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_BODY));
    }

    @Test
    void shouldFailWithBlankOperationString() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_OPERATION));
    }

    // ==================== Email validation ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "user@example.com",
            "first.last@domain.org",
            "user+tag@example.co.uk",
            "test123@sub.domain.com"
    })
    void shouldAcceptValidEmails(String email) {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        ToolResult result = tool.validateRecipients(email);

        assertTrue(result.isSuccess());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-email",
            "@missing-user.com",
            "user@",
            "user@.com",
            "user space@example.com"
    })
    void shouldRejectInvalidEmails(String email) {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        ToolResult result = tool.validateRecipients(email);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid email"));
    }

    @Test
    void shouldValidateMultipleRecipients() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        ToolResult result = tool.validateRecipients("a@example.com, b@example.com");

        assertTrue(result.isSuccess());
    }

    @Test
    void shouldRejectIfAnyRecipientInvalid() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        ToolResult result = tool.validateRecipients("valid@example.com, not-valid");

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not-valid"));
    }

    @Test
    void shouldFailSendWithInvalidRecipient() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_SEND_EMAIL,
                PARAM_TO, "bad-address",
                PARAM_SUBJECT, "Test",
                PARAM_BODY, "Hello")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid email"));
    }

    @Test
    void shouldFailWithInvalidCcRecipient() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "Hello");
        params.put("cc", "invalid@");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid email"));
    }

    @Test
    void shouldFailWithInvalidBccRecipient() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "Hello");
        params.put("bcc", "not-an-email");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid email"));
    }

    @Test
    void shouldSkipBlankCcParameter() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "Hello");
        params.put("cc", "   ");

        // This should NOT fail on CC validation — blank CC is skipped
        // It will fail on Transport.send() since we have no real SMTP server,
        // but it proves the CC validation was skipped
        ToolResult result = tool.execute(params).get();

        // Either success (unlikely without real SMTP) or error NOT about invalid email
        if (!result.isSuccess()) {
            assertFalse(result.getError().contains("Invalid email"));
        }
    }

    @Test
    void shouldSkipBlankBccParameter() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "Hello");
        params.put("bcc", "");

        ToolResult result = tool.execute(params).get();

        if (!result.isSuccess()) {
            assertFalse(result.getError().contains("Invalid email"));
        }
    }

    // ==================== reply_email ====================

    @Test
    void shouldFailReplyWithMissingTo() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_REPLY_EMAIL,
                PARAM_SUBJECT, "Re: Test",
                PARAM_BODY, "Reply body")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_TO));
    }

    @Test
    void shouldFailReplyWithInvalidRecipient() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_REPLY_EMAIL,
                PARAM_TO, "not-valid",
                PARAM_SUBJECT, "Re: Test",
                PARAM_BODY, "Reply body")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid email"));
    }

    @Test
    void shouldRejectEmptyRecipientBetweenCommas() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        ToolResult result = tool.validateRecipients("a@example.com,,b@example.com");

        assertFalse(result.isSuccess());
    }

    // ==================== Error handling ====================

    @Test
    void shouldSanitizeCredentialsInError() {
        smtpConfig.setEnabled(true);
        smtpConfig.setHost(SMTP_HOST);
        smtpConfig.setUsername(USERNAME);
        smtpConfig.setPassword("secret123");

        SmtpTool tool = new SmtpTool(runtimeConfigService);

        String sanitized = tool.sanitizeError("Login failed for " + USERNAME + " with password secret123");

        assertTrue(sanitized.contains("***"));
        assertFalse(sanitized.contains(USERNAME));
        assertFalse(sanitized.contains("secret123"));
    }

    @Test
    void shouldHandleNullErrorMessage() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        assertEquals("Unknown error", tool.sanitizeError(null));
    }

    @Test
    void shouldSanitizeErrorWithNoCredentials() {
        SmtpTool tool = new SmtpTool(runtimeConfigService);

        String sanitized = tool.sanitizeError("Connection timed out");

        assertEquals("Connection timed out", sanitized);
    }

    @Test
    void shouldHandleSmtpConnectionError() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        // Attempting to send with a fake SMTP host will throw
        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_SEND_EMAIL,
                PARAM_TO, TEST_RECIPIENT,
                PARAM_SUBJECT, "Test",
                PARAM_BODY, "Hello")).get();

        // Should fail gracefully with SMTP error, not crash
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    @Test
    void shouldHandleReplyConnectionError() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_REPLY_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Test");
        params.put(PARAM_BODY, "Reply");
        params.put("message_id", "<original@example.com>");

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    @Test
    void shouldHandleReplyWithReferencesConnectionError() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_REPLY_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Original Subject");
        params.put(PARAM_BODY, "Reply");
        params.put("message_id", "<msg@example.com>");
        params.put("references", "<prev@example.com>");

        ToolResult result = tool.execute(params).get();

        // Will fail due to no real SMTP, but exercises the reply headers code path
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    @Test
    void shouldHandleReplyWithExistingRePrefix() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_REPLY_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Re: Already prefixed");
        params.put(PARAM_BODY, "Reply");

        ToolResult result = tool.execute(params).get();

        // Will fail at transport but exercises the Re: prefix detection path
        assertFalse(result.isSuccess());
    }

    @Test
    void shouldHandleHtmlSendAttempt() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "HTML Email");
        params.put(PARAM_BODY, "<h1>Hello</h1>");
        params.put("html", Boolean.TRUE);

        ToolResult result = tool.execute(params).get();

        // Will fail at transport, but exercises html=true code path
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    @Test
    void shouldHandleSendWithValidCcAndBcc() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "CC BCC Test");
        params.put(PARAM_BODY, "Hello");
        params.put("cc", "cc@example.com");
        params.put("bcc", "bcc@example.com");

        ToolResult result = tool.execute(params).get();

        // Will fail at transport, but exercises CC/BCC address setting
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    // ==================== Helpers ====================

    private void configureProperties() {
        smtpConfig.setEnabled(true);
        smtpConfig.setHost(SMTP_HOST);
        smtpConfig.setUsername(USERNAME);
        smtpConfig.setPassword(PASSWORD);
    }

    private SmtpTool createConfiguredTool() {
        configureProperties();
        return new SmtpTool(runtimeConfigService);
    }
}
