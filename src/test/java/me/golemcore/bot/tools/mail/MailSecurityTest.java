package me.golemcore.bot.tools.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class MailSecurityTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void shouldDefaultToSslForNullOrBlankInput(String input) {
        assertEquals(MailSecurity.SSL, MailSecurity.fromString(input));
    }

    @Test
    void shouldParseSslLowercase() {
        assertEquals(MailSecurity.SSL, MailSecurity.fromString("ssl"));
    }

    @Test
    void shouldParseSslUppercase() {
        assertEquals(MailSecurity.SSL, MailSecurity.fromString("SSL"));
    }

    @Test
    void shouldParseSslMixedCase() {
        assertEquals(MailSecurity.SSL, MailSecurity.fromString("Ssl"));
    }

    @Test
    void shouldParseStarttlsLowercase() {
        assertEquals(MailSecurity.STARTTLS, MailSecurity.fromString("starttls"));
    }

    @Test
    void shouldParseStarttlsUppercase() {
        assertEquals(MailSecurity.STARTTLS, MailSecurity.fromString("STARTTLS"));
    }

    @Test
    void shouldParseNoneLowercase() {
        assertEquals(MailSecurity.NONE, MailSecurity.fromString("none"));
    }

    @Test
    void shouldParseNoneUppercase() {
        assertEquals(MailSecurity.NONE, MailSecurity.fromString("NONE"));
    }

    @Test
    void shouldThrowForInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> MailSecurity.fromString("tls"));
    }

    @Test
    void shouldHaveThreeEnumValues() {
        assertEquals(3, MailSecurity.values().length);
    }
}
