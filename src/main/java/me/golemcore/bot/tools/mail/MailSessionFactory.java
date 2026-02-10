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

package me.golemcore.bot.tools.mail;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

import java.util.Properties;

/**
 * Utility class for creating Jakarta Mail sessions with appropriate security
 * settings (SSL, STARTTLS, or plaintext).
 *
 * <p>
 * Not a Spring bean — used directly by ImapTool and SmtpTool.
 */
public final class MailSessionFactory {

    private static final String MAIL_PREFIX = "mail.";
    private static final String TRUE_VALUE = "true";

    private MailSessionFactory() {
    }

    /**
     * Creates a Jakarta Mail session configured for IMAP.
     *
     * @param host
     *            IMAP server hostname
     * @param port
     *            IMAP server port
     * @param username
     *            authentication username
     * @param password
     *            authentication password
     * @param security
     *            connection security mode
     * @param connectTimeout
     *            connection timeout in milliseconds
     * @param readTimeout
     *            read timeout in milliseconds
     * @return configured Mail Session
     */
    public static Session createImapSession(String host, int port, String username, String password,
            MailSecurity security, int connectTimeout, int readTimeout) {

        Properties props = new Properties();
        String protocol = (security == MailSecurity.SSL) ? "imaps" : "imap";
        String prefix = MAIL_PREFIX + protocol + ".";

        props.put("mail.store.protocol", protocol);
        props.put(prefix + "host", host);
        props.put(prefix + "port", String.valueOf(port));
        props.put(prefix + "connectiontimeout", String.valueOf(connectTimeout));
        props.put(prefix + "timeout", String.valueOf(readTimeout));

        if (security == MailSecurity.SSL) {
            props.put(MAIL_PREFIX + "imaps.ssl.enable", TRUE_VALUE);
        } else if (security == MailSecurity.STARTTLS) {
            props.put(MAIL_PREFIX + "imap.starttls.enable", TRUE_VALUE);
            props.put(MAIL_PREFIX + "imap.starttls.required", TRUE_VALUE);
            props.put(MAIL_PREFIX + "imap.ssl.trust", "*");
        }

        return Session.getInstance(props, createAuthenticator(username, password));
    }

    /**
     * Creates a Jakarta Mail session configured for SMTP.
     *
     * @param host
     *            SMTP server hostname
     * @param port
     *            SMTP server port
     * @param username
     *            authentication username
     * @param password
     *            authentication password
     * @param security
     *            connection security mode
     * @param connectTimeout
     *            connection timeout in milliseconds
     * @param readTimeout
     *            read timeout in milliseconds
     * @return configured Mail Session
     */
    public static Session createSmtpSession(String host, int port, String username, String password,
            MailSecurity security, int connectTimeout, int readTimeout) {

        Properties props = new Properties();
        String protocol = (security == MailSecurity.SSL) ? "smtps" : "smtp";
        String prefix = MAIL_PREFIX + protocol + ".";

        props.put("mail.transport.protocol", protocol);
        props.put(prefix + "host", host);
        props.put(prefix + "port", String.valueOf(port));
        props.put(prefix + "auth", TRUE_VALUE);
        props.put(prefix + "connectiontimeout", String.valueOf(connectTimeout));
        props.put(prefix + "timeout", String.valueOf(readTimeout));

        if (security == MailSecurity.SSL) {
            props.put(MAIL_PREFIX + "smtps.ssl.enable", TRUE_VALUE);
        } else if (security == MailSecurity.STARTTLS) {
            props.put(MAIL_PREFIX + "smtp.starttls.enable", TRUE_VALUE);
            props.put(MAIL_PREFIX + "smtp.starttls.required", TRUE_VALUE);
            props.put(MAIL_PREFIX + "smtp.ssl.trust", "*");
        }

        return Session.getInstance(props, createAuthenticator(username, password));
    }

    private static Authenticator createAuthenticator(String username, String password) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        };
    }
}
