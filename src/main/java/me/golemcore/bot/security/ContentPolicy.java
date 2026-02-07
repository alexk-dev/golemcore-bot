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

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Content policy enforcement component for detecting and redacting sensitive
 * information.
 *
 * <p>
 * This component provides three main capabilities:
 * <ul>
 * <li>Content validation - checks for policy violations like excessive length
 * or sensitive data</li>
 * <li>Sensitive data redaction - removes API keys, passwords, tokens,
 * emails</li>
 * <li>Content truncation - limits content to maximum allowed length</li>
 * </ul>
 *
 * <p>
 * Detected patterns include:
 * <ul>
 * <li>Passwords and API keys in various formats</li>
 * <li>Bearer authentication tokens</li>
 * <li>Email addresses</li>
 * </ul>
 *
 * @since 1.0
 */
@Component
public class ContentPolicy {

    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 10000;
    private static final int DEFAULT_MAX_TOOL_OUTPUT_LENGTH = 50000;

    // Patterns for potentially harmful content
    private static final List<Pattern> HARMFUL_PATTERNS = List.of(
            Pattern.compile("password\\s*[:=]\\s*['\"][^'\"]+['\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("api[_-]?key\\s*[:=]\\s*['\"][^'\"]+['\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("secret\\s*[:=]\\s*['\"][^'\"]+['\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Bearer\\s+[A-Za-z0-9\\-._~+/]+=*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b") // Email
    );

    /**
     * Check if content violates policy.
     */
    public PolicyCheckResult checkContent(String content) {
        if (content == null || content.isBlank()) {
            return PolicyCheckResult.ok();
        }

        List<String> violations = new java.util.ArrayList<>();

        // Check length
        if (content.length() > DEFAULT_MAX_MESSAGE_LENGTH) {
            violations.add("Content exceeds maximum length");
        }

        // Check for potentially sensitive data
        for (Pattern pattern : HARMFUL_PATTERNS) {
            if (pattern.matcher(content).find()) {
                violations.add("Content may contain sensitive data");
                break;
            }
        }

        if (violations.isEmpty()) {
            return PolicyCheckResult.ok();
        }

        return PolicyCheckResult.violation(violations);
    }

    /**
     * Redact sensitive information from content.
     */
    public String redactSensitive(String content) {
        if (content == null) {
            return "";
        }

        String result = content;

        // Redact potential passwords
        result = result.replaceAll(
                "(password\\s*[:=]\\s*['\"])[^'\"]+(['\"])",
                "$1[REDACTED]$2");

        // Redact potential API keys
        result = result.replaceAll(
                "(api[_-]?key\\s*[:=]\\s*['\"])[^'\"]+(['\"])",
                "$1[REDACTED]$2");

        // Redact Bearer tokens
        result = result.replaceAll(
                "Bearer\\s+[A-Za-z0-9\\-._~+/]+=*",
                "Bearer [REDACTED]");

        return result;
    }

    /**
     * Truncate content to maximum length.
     */
    public String truncate(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength - 3) + "...";
    }

    public record PolicyCheckResult(boolean isOk, List<String> violations) {
        public static PolicyCheckResult ok() {
            return new PolicyCheckResult(true, List.of());
        }

        public static PolicyCheckResult violation(List<String> violations) {
            return new PolicyCheckResult(false, violations);
        }
    }
}
