package me.golemcore.bot.domain.component;

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

/**
 * Component for input sanitization and security validation. Provides HTML
 * sanitization, injection detection, and content filtering to protect against
 * malicious inputs. Used by InputSanitizationSystem in the agent pipeline.
 */
public interface SanitizerComponent extends Component {

    @Override
    default String getComponentType() {
        return "sanitizer";
    }

    /**
     * Sanitizes input text by removing potentially harmful content.
     *
     * @param input
     *            the raw input text
     * @return the sanitized text
     */
    String sanitize(String input);

    /**
     * Checks if the input is safe and returns detailed sanitization results.
     *
     * @param input
     *            the input text to check
     * @return a SanitizationResult containing safety status and detected threats
     */
    SanitizationResult check(String input);

    /**
     * Result of input sanitization containing safety status, sanitized content, and threat list.
     *
     * @param safe true if the input is safe, false if threats were detected
     * @param sanitizedInput the sanitized version of the input
     * @param threats list of detected threat types (e.g., "html_injection", "path_traversal")
     */
    record SanitizationResult(
            boolean safe,
            String sanitizedInput,
            java.util.List<String> threats
    ) {
        public static SanitizationResult safe(String input) {
            return new SanitizationResult(true, input, java.util.List.of());
        }

        public static SanitizationResult unsafe(String sanitized, java.util.List<String> threats) {
            return new SanitizationResult(false, sanitized, threats);
        }
    }
}
