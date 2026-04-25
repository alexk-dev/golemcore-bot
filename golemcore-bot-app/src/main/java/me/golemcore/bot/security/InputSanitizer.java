package me.golemcore.bot.security;

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

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * Input sanitizer that normalizes Unicode and removes invisible/control
 * characters.
 *
 * <p>
 * This component provides protection against:
 * <ul>
 * <li>Homograph attacks - normalizes Unicode to canonical NFC form</li>
 * <li>Invisible character injection - removes zero-width and control
 * characters</li>
 * <li>Text direction override attacks - removes BiDi control characters</li>
 * </ul>
 *
 * <p>
 * The sanitizer preserves legitimate whitespace (newline, tab) while removing
 * potentially malicious characters that could be used for obfuscation or
 * display manipulation.
 *
 * @since 1.0
 */
@Component
@Slf4j
public class InputSanitizer {

    /**
     * Normalize Unicode to prevent homograph attacks. Converts to NFC form and
     * removes invisible characters.
     */
    public String normalizeUnicode(String input) {
        if (input == null) {
            return "";
        }

        // Normalize to NFC form
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);

        // Remove zero-width and invisible characters (extended set)
        normalized = normalized.replaceAll(
                "[\\u200B-\\u200F\\uFEFF\\u2060\\u00AD\\u061C\\u180E\\u202A-\\u202E\\u2066-\\u2069]", "");

        // Remove control characters except newline/tab
        normalized = normalized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        return normalized;
    }

    /**
     * Full sanitization pipeline.
     */
    public String sanitize(String input) {
        if (input == null) {
            return "";
        }

        log.trace("[Security] Sanitizing input: {} chars", input.length());
        int originalLength = input.length();
        input = normalizeUnicode(input);
        if (input.length() != originalLength) {
            log.debug("[Security] Input sanitized: {} → {} chars", originalLength, input.length());
        }
        return input;
    }
}
