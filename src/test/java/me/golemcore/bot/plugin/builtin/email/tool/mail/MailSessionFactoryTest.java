package me.golemcore.bot.plugin.builtin.email.tool.mail;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("PMD.AvoidDuplicateLiterals") // Test readability over deduplication
class MailSessionFactoryTest {

    private static final String IMAP_HOST = "imap.example.com";
    private static final String SMTP_HOST = "smtp.example.com";
    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "secret";
    private static final String TRUE_STR = "true";

    // ==================== IMAP ====================

    @Test
    void shouldCreateImapSessionWithSsl() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 993, USERNAME, PASSWORD,
                MailSecurity.SSL, "", 10000, 30000);

        Properties props = session.getProperties();
        assertEquals("imaps", props.getProperty("mail.store.protocol"));
        assertEquals(IMAP_HOST, props.getProperty("mail.imaps.host"));
        assertEquals("993", props.getProperty("mail.imaps.port"));
        assertEquals("10000", props.getProperty("mail.imaps.connectiontimeout"));
        assertEquals("30000", props.getProperty("mail.imaps.timeout"));
        assertEquals(TRUE_STR, props.getProperty("mail.imaps.ssl.enable"));
        assertNull(props.getProperty("mail.imaps.ssl.trust"));
    }

    @Test
    void shouldCreateImapSessionWithStarttls() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 143, USERNAME, PASSWORD,
                MailSecurity.STARTTLS, "", 5000, 15000);

        Properties props = session.getProperties();
        assertEquals("imap", props.getProperty("mail.store.protocol"));
        assertEquals(IMAP_HOST, props.getProperty("mail.imap.host"));
        assertEquals("143", props.getProperty("mail.imap.port"));
        assertEquals(TRUE_STR, props.getProperty("mail.imap.starttls.enable"));
        assertEquals(TRUE_STR, props.getProperty("mail.imap.starttls.required"));
        assertNull(props.getProperty("mail.imap.ssl.trust"));
        assertEquals("5000", props.getProperty("mail.imap.connectiontimeout"));
        assertEquals("15000", props.getProperty("mail.imap.timeout"));
    }

    @Test
    void shouldCreateImapSessionWithNoSecurity() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 143, USERNAME, PASSWORD,
                MailSecurity.NONE, "", 10000, 30000);

        Properties props = session.getProperties();
        assertEquals("imap", props.getProperty("mail.store.protocol"));
        assertNull(props.getProperty("mail.imap.starttls.enable"));
        assertNull(props.getProperty("mail.imaps.ssl.enable"));
        assertNull(props.getProperty("mail.imap.ssl.trust"));
    }

    @Test
    void shouldSetImapSslTrustWhenProvided() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 993, USERNAME, PASSWORD,
                MailSecurity.SSL, "*", 10000, 30000);

        Properties props = session.getProperties();
        assertEquals("*", props.getProperty("mail.imaps.ssl.trust"));
    }

    @Test
    void shouldSetImapSslTrustForStarttls() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 143, USERNAME, PASSWORD,
                MailSecurity.STARTTLS, "mail.example.com", 5000, 15000);

        Properties props = session.getProperties();
        assertEquals("mail.example.com", props.getProperty("mail.imap.ssl.trust"));
    }

    @Test
    void shouldNotSetImapSslTrustWhenNull() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 993, USERNAME, PASSWORD,
                MailSecurity.SSL, null, 10000, 30000);

        Properties props = session.getProperties();
        assertNull(props.getProperty("mail.imaps.ssl.trust"));
    }

    @Test
    void shouldNotSetImapSslTrustWhenBlank() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 993, USERNAME, PASSWORD,
                MailSecurity.SSL, "   ", 10000, 30000);

        Properties props = session.getProperties();
        assertNull(props.getProperty("mail.imaps.ssl.trust"));
    }

    // ==================== SMTP ====================

    @Test
    void shouldCreateSmtpSessionWithSsl() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 465, USERNAME, PASSWORD,
                MailSecurity.SSL, "", 10000, 30000);

        Properties props = session.getProperties();
        assertEquals("smtps", props.getProperty("mail.transport.protocol"));
        assertEquals(SMTP_HOST, props.getProperty("mail.smtps.host"));
        assertEquals("465", props.getProperty("mail.smtps.port"));
        assertEquals(TRUE_STR, props.getProperty("mail.smtps.auth"));
        assertEquals(TRUE_STR, props.getProperty("mail.smtps.ssl.enable"));
        assertEquals("10000", props.getProperty("mail.smtps.connectiontimeout"));
        assertEquals("30000", props.getProperty("mail.smtps.timeout"));
        assertNull(props.getProperty("mail.smtps.ssl.trust"));
    }

    @Test
    void shouldCreateSmtpSessionWithStarttls() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 587, USERNAME, PASSWORD,
                MailSecurity.STARTTLS, "", 5000, 15000);

        Properties props = session.getProperties();
        assertEquals("smtp", props.getProperty("mail.transport.protocol"));
        assertEquals(SMTP_HOST, props.getProperty("mail.smtp.host"));
        assertEquals("587", props.getProperty("mail.smtp.port"));
        assertEquals(TRUE_STR, props.getProperty("mail.smtp.auth"));
        assertEquals(TRUE_STR, props.getProperty("mail.smtp.starttls.enable"));
        assertEquals(TRUE_STR, props.getProperty("mail.smtp.starttls.required"));
        assertNull(props.getProperty("mail.smtp.ssl.trust"));
        assertEquals("5000", props.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("15000", props.getProperty("mail.smtp.timeout"));
    }

    @Test
    void shouldCreateSmtpSessionWithNoSecurity() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 25, USERNAME, PASSWORD,
                MailSecurity.NONE, "", 10000, 30000);

        Properties props = session.getProperties();
        assertEquals("smtp", props.getProperty("mail.transport.protocol"));
        assertNull(props.getProperty("mail.smtp.starttls.enable"));
        assertNull(props.getProperty("mail.smtps.ssl.enable"));
        assertNull(props.getProperty("mail.smtp.ssl.trust"));
    }

    @Test
    void shouldSetSmtpSslTrustWhenProvided() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 465, USERNAME, PASSWORD,
                MailSecurity.SSL, "*", 10000, 30000);

        Properties props = session.getProperties();
        assertEquals("*", props.getProperty("mail.smtps.ssl.trust"));
    }

    @Test
    void shouldSetSmtpSslTrustForStarttls() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 587, USERNAME, PASSWORD,
                MailSecurity.STARTTLS, "localhost bridge.local", 5000, 15000);

        Properties props = session.getProperties();
        assertEquals("localhost bridge.local", props.getProperty("mail.smtp.ssl.trust"));
    }

    @Test
    void shouldNotSetSmtpSslTrustWhenNull() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 465, USERNAME, PASSWORD,
                MailSecurity.SSL, null, 10000, 30000);

        Properties props = session.getProperties();
        assertNull(props.getProperty("mail.smtps.ssl.trust"));
    }

    @Test
    void shouldNotSetSmtpSslTrustWhenBlank() {
        Session session = MailSessionFactory.createSmtpSession(
                SMTP_HOST, 465, USERNAME, PASSWORD,
                MailSecurity.SSL, "   ", 10000, 30000);

        Properties props = session.getProperties();
        assertNull(props.getProperty("mail.smtps.ssl.trust"));
    }

    // ==================== General ====================

    @Test
    void shouldIncludeAuthenticator() {
        Session session = MailSessionFactory.createImapSession(
                IMAP_HOST, 993, USERNAME, PASSWORD,
                MailSecurity.SSL, "", 10000, 30000);

        assertNotNull(session);
    }
}
