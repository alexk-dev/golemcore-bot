package me.golemcore.bot.domain.system;

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
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import me.golemcore.bot.domain.model.ContextAttributes;

/**
 * System for input sanitization and security validation (order=10, first in
 * pipeline). Applies HTML sanitization, length limits, injection detection
 * (prompt, command, SQL, path traversal) via
 * {@link domain.component.SanitizerComponent}. Blocks processing if threats are
 * detected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InputSanitizationSystem implements AgentSystem {

    private final SanitizerComponent sanitizerComponent;

    @Override
    public String getName() {
        return "InputSanitizationSystem";
    }

    @Override
    public int getOrder() {
        return 10; // First in the pipeline
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return context;
        }

        // Get the last user message
        Message lastMessage = context.getMessages().get(context.getMessages().size() - 1);
        if (!lastMessage.isUserMessage()) {
            return context;
        }

        String originalContent = lastMessage.getContent();
        if (originalContent == null || originalContent.isBlank()) {
            return context;
        }

        // Check and sanitize
        SanitizerComponent.SanitizationResult result = sanitizerComponent.check(originalContent);

        if (!result.safe()) {
            log.warn("Detected threats in input: {}", result.threats());
            // Update the message with sanitized content
            lastMessage.setContent(result.sanitizedInput());
            context.setAttribute(ContextAttributes.SANITIZATION_THREATS, result.threats());
        }

        context.setAttribute(ContextAttributes.SANITIZATION_PERFORMED, true);
        return context;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // Only process if there are messages and sanitization is enabled
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        // Skip sanitization for auto-mode synthetic messages (internally generated,
        // trusted)
        if (isAutoModeMessage(context)) {
            return false;
        }
        return true;
    }

    private boolean isAutoModeMessage(AgentContext context) {
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }
}
