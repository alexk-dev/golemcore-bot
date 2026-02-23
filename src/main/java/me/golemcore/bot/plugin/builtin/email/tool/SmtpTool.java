/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.plugin.builtin.email.tool;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.plugin.builtin.email.tool.mail.MailSecurity;
import me.golemcore.bot.plugin.builtin.email.tool.mail.MailSessionFactory;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Tool for sending email via SMTP.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>send_email — send a new email
 * <li>reply_email — reply with proper In-Reply-To/References headers
 * </ul>
 *
 * <p>
 * The From address is always the configured username — the LLM cannot spoof the
 * sender.
 *
 * <p>
 * Configuration: {@code bot.tools.smtp.*}
 */
@Component
@Slf4j
@SuppressWarnings("PMD.ReplaceJavaUtilDate") // jakarta.mail.internet.MimeMessage.setSentDate requires java.util.Date
public class SmtpTool implements ToolComponent {

    private static final String PARAM_TYPE = "type";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_OBJECT = "object";
    private static final String SCHEMA_DESC = "description";

    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_TO = "to";
    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_BODY = "body";
    private static final String PARAM_CC = "cc";
    private static final String PARAM_BCC = "bcc";
    private static final String PARAM_HTML = "html";
    private static final String PARAM_MESSAGE_ID = "message_id";
    private static final String PARAM_REFERENCES = "references";

    static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private final RuntimeConfigService runtimeConfigService;

    public SmtpTool(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @Override
    public boolean isEnabled() {
        BotProperties.SmtpToolProperties config = getResolvedConfig();
        return config.isEnabled()
                && config.getHost() != null && !config.getHost().isBlank()
                && config.getUsername() != null && !config.getUsername().isBlank();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("smtp")
                .description(
                        """
                                Send email via SMTP. Operations: send_email, reply_email.
                                Recipients can be comma-separated for multiple addresses.
                                The From address is always the configured account.
                                For replies, provide message_id to set proper threading headers.
                                """)
                .inputSchema(Map.of(
                        PARAM_TYPE, TYPE_OBJECT,
                        "properties", Map.of(
                                PARAM_OPERATION, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        "enum", List.of("send_email", "reply_email"),
                                        SCHEMA_DESC, "Operation to perform"),
                                PARAM_TO, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Recipient email addresses (comma-separated)"),
                                PARAM_SUBJECT, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Email subject"),
                                PARAM_BODY, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Email body text"),
                                PARAM_CC, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "CC recipients (comma-separated)"),
                                PARAM_BCC, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "BCC recipients (comma-separated)"),
                                PARAM_HTML, Map.of(
                                        PARAM_TYPE, TYPE_BOOLEAN,
                                        SCHEMA_DESC, "Send as HTML (default: false)"),
                                PARAM_MESSAGE_ID, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Original Message-ID for reply threading"),
                                PARAM_REFERENCES, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "References header for reply threading")),
                        "required", List.of(PARAM_OPERATION, PARAM_TO, PARAM_SUBJECT, PARAM_BODY)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String operation = (String) parameters.get(PARAM_OPERATION);
            if (operation == null || operation.isBlank()) {
                return ToolResult.failure("Missing required parameter: operation");
            }

            log.info("[SMTP] Operation: {}", operation);

            try {
                return switch (operation) {
                case "send_email" -> sendEmail(parameters, false);
                case "reply_email" -> sendEmail(parameters, true);
                default -> ToolResult.failure("Unknown operation: " + operation);
                };
            } catch (AuthenticationFailedException e) {
                log.error("[SMTP] Authentication failed", e);
                return ToolResult.failure("SMTP authentication failed. Check username and password.");
            } catch (MessagingException e) {
                log.error("[SMTP] Messaging error: {}", e.getMessage(), e);
                return ToolResult.failure("SMTP error: " + sanitizeError(e.getMessage()));
            } catch (Exception e) { // NOSONAR - catch broadly for I/O layer
                log.error("[SMTP] Error: {}", e.getMessage(), e);
                return ToolResult.failure("SMTP error: " + sanitizeError(e.getMessage()));
            }
        });
    }

    private ToolResult sendEmail(Map<String, Object> params, boolean isReply) throws MessagingException {
        BotProperties.SmtpToolProperties config = getResolvedConfig();
        MailSecurity security = MailSecurity.fromString(config.getSecurity());
        String to = (String) params.get(PARAM_TO);
        String subject = (String) params.get(PARAM_SUBJECT);
        String body = (String) params.get(PARAM_BODY);

        if (to == null || to.isBlank()) {
            return ToolResult.failure("Missing required parameter: to");
        }
        if (subject == null || subject.isBlank()) {
            return ToolResult.failure("Missing required parameter: subject");
        }
        if (body == null || body.isBlank()) {
            return ToolResult.failure("Missing required parameter: body");
        }

        // Validate recipients
        ToolResult validation = validateRecipients(to);
        if (!validation.isSuccess()) {
            return validation;
        }

        String cc = (String) params.get(PARAM_CC);
        if (cc != null && !cc.isBlank()) {
            ToolResult ccValidation = validateRecipients(cc);
            if (!ccValidation.isSuccess()) {
                return ccValidation;
            }
        }

        String bcc = (String) params.get(PARAM_BCC);
        if (bcc != null && !bcc.isBlank()) {
            ToolResult bccValidation = validateRecipients(bcc);
            if (!bccValidation.isSuccess()) {
                return bccValidation;
            }
        }

        boolean html = Boolean.TRUE.equals(params.get(PARAM_HTML));

        Session session = MailSessionFactory.createSmtpSession(
                config.getHost(), config.getPort(),
                config.getUsername(), config.getPassword(),
                security, config.getSslTrust(),
                config.getConnectTimeout(), config.getReadTimeout());

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getUsername()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));

        if (cc != null && !cc.isBlank()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }
        if (bcc != null && !bcc.isBlank()) {
            message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
        }

        // Reply headers
        if (isReply) {
            String messageId = (String) params.get(PARAM_MESSAGE_ID);
            if (messageId != null && !messageId.isBlank()) {
                message.setHeader("In-Reply-To", messageId);

                String references = (String) params.get(PARAM_REFERENCES);
                if (references != null && !references.isBlank()) {
                    message.setHeader("References", references + " " + messageId);
                } else {
                    message.setHeader("References", messageId);
                }
            }

            // Auto-prepend Re: if missing
            if (!subject.regionMatches(true, 0, "Re:", 0, 3)) {
                subject = "Re: " + subject;
            }
        }

        message.setSubject(subject, "UTF-8");
        message.setContent(body, html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8");
        message.setSentDate(new Date());

        deliver(message);

        log.info("[SMTP] Email sent to: {}", to);

        String action = isReply ? "Reply sent" : "Email sent";
        return ToolResult.success(
                String.format("%s to %s (subject: %s)", action, to, subject),
                Map.of(PARAM_TO, to, PARAM_SUBJECT, subject, "reply", isReply));
    }

    ToolResult validateRecipients(String recipients) {
        String[] addresses = recipients.split(",");
        List<String> invalid = new ArrayList<>();
        for (String addr : addresses) {
            String trimmed = addr.trim();
            if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
                invalid.add(trimmed);
            }
        }
        if (!invalid.isEmpty()) {
            return ToolResult.failure("Invalid email address: " + String.join(", ", invalid));
        }
        return ToolResult.success("OK");
    }

    String sanitizeError(String message) {
        if (message == null) {
            return "Unknown error";
        }
        BotProperties.SmtpToolProperties config = getResolvedConfig();
        String sanitized = message;
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            sanitized = sanitized.replace(config.getUsername(), "***");
        }
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            sanitized = sanitized.replace(config.getPassword(), "***");
        }
        return sanitized;
    }

    private BotProperties.SmtpToolProperties getResolvedConfig() {
        return runtimeConfigService.getResolvedSmtpConfig();
    }

    protected void deliver(MimeMessage message) throws MessagingException {
        Transport.send(message);
    }
}
