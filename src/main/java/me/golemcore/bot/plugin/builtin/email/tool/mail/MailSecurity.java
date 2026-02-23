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

package me.golemcore.bot.plugin.builtin.email.tool.mail;

import java.util.Locale;

/**
 * Connection security modes for IMAP and SMTP mail connections.
 */
public enum MailSecurity {

    /** Implicit SSL/TLS on dedicated port (e.g. 993 IMAP, 465 SMTP). */
    SSL,

    /** STARTTLS upgrade on plain-text port (e.g. 143 IMAP, 587 SMTP). */
    STARTTLS,

    /** No encryption (not recommended). */
    NONE;

    /**
     * Parses a security mode from a string value (case-insensitive).
     *
     * @param value
     *            the string value ("ssl", "starttls", or "none")
     * @return the corresponding MailSecurity enum value
     * @throws IllegalArgumentException
     *             if the value is not recognized
     */
    public static MailSecurity fromString(String value) {
        if (value == null || value.isBlank()) {
            return SSL;
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
