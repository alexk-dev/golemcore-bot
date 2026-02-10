package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class SmtpToolTest {

    private static final String SMTP_HOST = "smtp.example.com";
    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "secret";
    private static final String PARAM_OPERATION = "operation";
    private static final String OP_SEND_EMAIL = "send_email";
    private static final String PARAM_TO = "to";
    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_BODY = "body";
    private static final String TEST_RECIPIENT = "test@example.com";

    private BotProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
    }

    // ==================== isEnabled ====================

    @Test
    void shouldBeDisabledByDefault() {
        SmtpTool tool = new SmtpTool(properties);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoHost() {
        BotProperties.SmtpToolProperties config = properties.getTools().getSmtp();
        config.setEnabled(true);
        config.setHost("");
        config.setUsername(USERNAME);

        SmtpTool tool = new SmtpTool(properties);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoUsername() {
        BotProperties.SmtpToolProperties config = properties.getTools().getSmtp();
        config.setEnabled(true);
        config.setHost(SMTP_HOST);
        config.setUsername("");

        SmtpTool tool = new SmtpTool(properties);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeEnabledWhenFullyConfigured() {
        configureProperties();

        SmtpTool tool = new SmtpTool(properties);

        assertTrue(tool.isEnabled());
    }

    // ==================== Definition ====================

    @Test
    void shouldReturnCorrectDefinition() {
        SmtpTool tool = new SmtpTool(properties);

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

    // ==================== Email validation ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "user@example.com",
            "first.last@domain.org",
            "user+tag@example.co.uk",
            "test123@sub.domain.com"
    })
    void shouldAcceptValidEmails(String email) {
        SmtpTool tool = new SmtpTool(properties);

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
        SmtpTool tool = new SmtpTool(properties);

        ToolResult result = tool.validateRecipients(email);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Invalid email"));
    }

    @Test
    void shouldValidateMultipleRecipients() {
        SmtpTool tool = new SmtpTool(properties);

        ToolResult result = tool.validateRecipients("a@example.com, b@example.com");

        assertTrue(result.isSuccess());
    }

    @Test
    void shouldRejectIfAnyRecipientInvalid() {
        SmtpTool tool = new SmtpTool(properties);

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

    // ==================== Error handling ====================

    @Test
    void shouldSanitizeCredentialsInError() {
        BotProperties.SmtpToolProperties config = properties.getTools().getSmtp();
        config.setEnabled(true);
        config.setHost(SMTP_HOST);
        config.setUsername(USERNAME);
        config.setPassword("secret123");

        SmtpTool tool = new SmtpTool(properties);

        String sanitized = tool.sanitizeError("Login failed for " + USERNAME + " with password secret123");

        assertTrue(sanitized.contains("***"));
        assertFalse(sanitized.contains(USERNAME));
        assertFalse(sanitized.contains("secret123"));
    }

    @Test
    void shouldHandleNullErrorMessage() {
        SmtpTool tool = new SmtpTool(properties);

        assertEquals("Unknown error", tool.sanitizeError(null));
    }

    // ==================== Helpers ====================

    private void configureProperties() {
        BotProperties.SmtpToolProperties config = properties.getTools().getSmtp();
        config.setEnabled(true);
        config.setHost(SMTP_HOST);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
    }

    private SmtpTool createConfiguredTool() {
        configureProperties();
        return new SmtpTool(properties);
    }
}
