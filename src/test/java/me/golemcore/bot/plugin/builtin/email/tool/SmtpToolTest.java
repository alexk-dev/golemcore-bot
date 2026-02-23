package me.golemcore.bot.plugin.builtin.email.tool;

import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

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

    @Test
    void shouldUseLatestRuntimeConfigForEnabledCheck() {
        BotProperties.SmtpToolProperties initialConfig = new BotProperties.SmtpToolProperties();
        BotProperties.SmtpToolProperties updatedConfig = new BotProperties.SmtpToolProperties();
        updatedConfig.setEnabled(true);
        updatedConfig.setHost(SMTP_HOST);
        updatedConfig.setUsername(USERNAME);
        updatedConfig.setPassword(PASSWORD);
        when(runtimeConfigService.getResolvedSmtpConfig()).thenReturn(initialConfig, updatedConfig);

        SmtpTool tool = new SmtpTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
        assertTrue(tool.isEnabled());
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

        ToolResult result = tool.execute(params).get();

        assertTrue(result.isSuccess());
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

        assertTrue(result.isSuccess());
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
        SmtpTool tool = createConfiguredFailingTool();

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, OP_SEND_EMAIL,
                PARAM_TO, TEST_RECIPIENT,
                PARAM_SUBJECT, "Test",
                PARAM_BODY, "Hello")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    @Test
    void shouldHandleReplyConnectionError() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredFailingTool();

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
        SmtpTool tool = createConfiguredFailingTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_REPLY_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "Original Subject");
        params.put(PARAM_BODY, "Reply");
        params.put("message_id", "<msg@example.com>");
        params.put("references", "<prev@example.com>");

        ToolResult result = tool.execute(params).get();

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

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Re: Already prefixed"));
    }

    @Test
    void shouldHandleHtmlSendAttempt() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredFailingTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "HTML Email");
        params.put(PARAM_BODY, "<h1>Hello</h1>");
        params.put("html", Boolean.TRUE);

        ToolResult result = tool.execute(params).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("SMTP"));
    }

    @Test
    void shouldHandleSendWithValidCcAndBcc() throws ExecutionException, InterruptedException {
        SmtpTool tool = createConfiguredFailingTool();

        Map<String, Object> params = new HashMap<>();
        params.put(PARAM_OPERATION, OP_SEND_EMAIL);
        params.put(PARAM_TO, TEST_RECIPIENT);
        params.put(PARAM_SUBJECT, "CC BCC Test");
        params.put(PARAM_BODY, "Hello");
        params.put("cc", "cc@example.com");
        params.put("bcc", "bcc@example.com");

        ToolResult result = tool.execute(params).get();

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
        return new SmtpTool(runtimeConfigService) {
            @Override
            protected void deliver(MimeMessage message) {
                // No-op in tests to avoid real SMTP connections.
            }
        };
    }

    private SmtpTool createConfiguredFailingTool() {
        configureProperties();
        return new SmtpTool(runtimeConfigService) {
            @Override
            protected void deliver(MimeMessage message) throws MessagingException {
                throw new MessagingException("Simulated SMTP failure");
            }
        };
    }
}
