package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.security.InjectionGuard;
import me.golemcore.bot.security.InputSanitizer;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service providing input validation and sanitization to protect against
 * security threats. Combines {@link InputSanitizer} for HTML cleaning and
 * Unicode normalization with {@link InjectionGuard} for detecting prompt
 * injection, command injection, SQL injection, and path traversal attacks. Used
 * by {@link domain.system.InputSanitizationSystem}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityService {

    private final InputSanitizer inputSanitizer;
    private final InjectionGuard injectionGuard;
    private final BotProperties properties;

    /**
     * Sanitize input text.
     */
    public String sanitizeInput(String input) {
        if (input == null) {
            return "";
        }

        // Apply length limit
        int maxLength = properties.getSecurity().getMaxInputLength();
        if (input.length() > maxLength) {
            input = input.substring(0, maxLength);
        }

        // Normalize Unicode
        input = inputSanitizer.normalizeUnicode(input);

        return input;
    }

    /**
     * Check for injection attacks.
     */
    public InjectionCheckResult checkForInjection(String input) {
        List<String> threats = new ArrayList<>();

        if (properties.getSecurity().isDetectPromptInjection()) {
            if (injectionGuard.detectPromptInjection(input)) {
                threats.add("prompt_injection");
            }
        }

        if (properties.getSecurity().isDetectCommandInjection()) {
            if (injectionGuard.detectCommandInjection(input)) {
                threats.add("command_injection");
            }
        }

        if (injectionGuard.detectSqlInjection(input)) {
            threats.add("sql_injection");
        }

        if (injectionGuard.detectPathTraversal(input)) {
            threats.add("path_traversal");
        }

        if (!threats.isEmpty()) {
            log.warn("Detected threats in input: {}", threats);
        }

        String sanitized = threats.isEmpty() ? input : sanitizeInput(input);

        return InjectionCheckResult.builder()
                .safe(threats.isEmpty())
                .threats(threats)
                .sanitizedInput(sanitized)
                .build();
    }

    /**
     * Check if a sender is in the allowlist.
     */
    public boolean isAllowed(String channelType, String senderId) {
        if (!properties.getSecurity().getAllowlist().isEnabled()) {
            return true;
        }

        BotProperties.ChannelProperties channelProps = properties.getChannels().get(channelType);
        if (channelProps == null) {
            return false;
        }

        List<String> allowedUsers = channelProps.getAllowFrom();
        if (allowedUsers == null || allowedUsers.isEmpty()) {
            return true; // No restriction
        }

        return allowedUsers.contains(senderId);
    }

    @Data
    @Builder
    public static class InjectionCheckResult {
        private boolean safe;
        private List<String> threats;
        private String sanitizedInput;
    }
}
