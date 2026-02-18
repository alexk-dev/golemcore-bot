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

import me.golemcore.bot.domain.component.SanitizerComponent;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default implementation of {@link SanitizerComponent}.
 *
 * <p>
 * Combines input sanitization with threat detection:
 * <ul>
 * <li>Uses {@link InputSanitizer} to normalize and clean input text</li>
 * <li>Uses {@link InjectionGuard} to detect injection attack patterns</li>
 * </ul>
 *
 * <p>
 * The {@link #check(String)} method returns detailed results including
 * sanitized content and any detected threats.
 *
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
public class DefaultSanitizerComponent implements SanitizerComponent {

    private final InputSanitizer inputSanitizer;
    private final InjectionGuard injectionGuard;
    private final RuntimeConfigService runtimeConfigService;

    @Override
    public String sanitize(String input) {
        if (!runtimeConfigService.isSanitizeInputEnabled()) {
            return input;
        }
        return inputSanitizer.sanitize(input);
    }

    @Override
    public SanitizationResult check(String input) {
        if (input != null && input.length() > runtimeConfigService.getMaxInputLength()) {
            String truncated = input.substring(0, runtimeConfigService.getMaxInputLength());
            return SanitizationResult.unsafe(truncated, List.of("input_too_long"));
        }

        List<String> threats = injectionGuard.detectAllThreats(input);

        if (threats.isEmpty()) {
            return SanitizationResult.safe(input);
        }

        String sanitized = runtimeConfigService.isSanitizeInputEnabled() ? inputSanitizer.sanitize(input) : input;
        return SanitizationResult.unsafe(sanitized, threats);
    }
}
