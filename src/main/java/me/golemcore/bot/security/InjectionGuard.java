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
import me.golemcore.bot.domain.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Security component that detects various injection attack patterns.
 *
 * <p>
 * Provides detection methods for:
 * <ul>
 * <li>Prompt injection - attempts to override system instructions</li>
 * <li>Command injection - malicious shell command sequences</li>
 * <li>SQL injection - database query manipulation attempts</li>
 * <li>Path traversal - directory traversal attacks</li>
 * </ul>
 *
 * <p>
 * Each detection method uses pattern matching against known attack signatures.
 * The component is stateless and thread-safe.
 *
 * @since 1.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class InjectionGuard {

    private final RuntimeConfigService runtimeConfigService;

    // Prompt injection patterns
    private static final List<Pattern> PROMPT_INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore (all )?(previous|above|prior) instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard (all )?(previous|above|prior)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget (everything|all|your)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you are now", Pattern.CASE_INSENSITIVE),
            Pattern.compile("new (role|persona|identity)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act as (a |an )?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend (to be|you're|you are)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[INST\\]|\\[/INST\\]|<<SYS>>|<</SYS>>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\|im_start\\|>|<\\|im_end\\|>", Pattern.CASE_INSENSITIVE));

    // Command injection patterns
    private static final List<Pattern> COMMAND_INJECTION_PATTERNS = List.of(
            Pattern.compile(";\\s*(rm|del|format|dd|mkfs)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\|\\s*(sh|bash|cmd|powershell)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("`[^`]+`"),
            Pattern.compile("\\$\\([^)]+\\)"),
            Pattern.compile("\\$\\{[^}]+\\}"),
            Pattern.compile("&&\\s*(rm|del|format)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\|\\|\\s*(rm|del|format)", Pattern.CASE_INSENSITIVE));

    // SQL injection patterns
    private static final List<Pattern> SQL_INJECTION_PATTERNS = List.of(
            Pattern.compile("'\\s*(OR|AND)\\s+'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("'\\s*=\\s*'"),
            Pattern.compile("(UNION|SELECT|INSERT|UPDATE|DELETE|DROP|ALTER|CREATE)\\s+(ALL\\s+)?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("--\\s*$"),
            Pattern.compile(";\\s*(DROP|DELETE|TRUNCATE)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("1\\s*=\\s*1"),
            Pattern.compile("'\\s*OR\\s+1\\s*=\\s*1", Pattern.CASE_INSENSITIVE));

    // Path traversal patterns
    private static final List<Pattern> PATH_TRAVERSAL_PATTERNS = List.of(
            Pattern.compile("\\.\\./"),
            Pattern.compile("\\.\\.\\\\"),
            Pattern.compile("%2e%2e%2f", Pattern.CASE_INSENSITIVE),
            Pattern.compile("%2e%2e/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\.\\.%2f", Pattern.CASE_INSENSITIVE),
            Pattern.compile("%2e%2e%5c", Pattern.CASE_INSENSITIVE),
            Pattern.compile("/etc/passwd", Pattern.CASE_INSENSITIVE),
            Pattern.compile("C:\\\\Windows", Pattern.CASE_INSENSITIVE));

    /**
     * Detect potential prompt injection attempts.
     */
    public boolean detectPromptInjection(String input) {
        if (!runtimeConfigService.isPromptInjectionDetectionEnabled()) {
            return false;
        }
        if (input == null || input.isBlank()) {
            return false;
        }

        for (Pattern pattern : PROMPT_INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("[Security] Prompt injection detected: pattern={}", pattern.pattern());
                return true;
            }
        }
        return false;
    }

    /**
     * Detect potential command injection attempts.
     */
    public boolean detectCommandInjection(String input) {
        if (!runtimeConfigService.isCommandInjectionDetectionEnabled()) {
            return false;
        }
        if (input == null || input.isBlank()) {
            return false;
        }

        for (Pattern pattern : COMMAND_INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("[Security] Command injection detected: pattern={}", pattern.pattern());
                return true;
            }
        }
        return false;
    }

    /**
     * Detect potential SQL injection attempts.
     */
    public boolean detectSqlInjection(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        for (Pattern pattern : SQL_INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("[Security] SQL injection detected: pattern={}", pattern.pattern());
                return true;
            }
        }
        return false;
    }

    /**
     * Detect path traversal attempts.
     */
    public boolean detectPathTraversal(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        for (Pattern pattern : PATH_TRAVERSAL_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("[Security] Path traversal detected: pattern={}", pattern.pattern());
                return true;
            }
        }
        return false;
    }

    /**
     * Check for any injection attempt.
     */
    public List<String> detectAllThreats(String input) {
        List<String> threats = new java.util.ArrayList<>();

        if (detectPromptInjection(input)) {
            threats.add("prompt_injection");
        }
        if (detectCommandInjection(input)) {
            threats.add("command_injection");
        }
        if (detectSqlInjection(input)) {
            threats.add("sql_injection");
        }
        if (detectPathTraversal(input)) {
            threats.add("path_traversal");
        }

        return threats;
    }
}
