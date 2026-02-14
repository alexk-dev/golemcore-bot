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

import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * System for persisting conversation exchanges to daily memory notes
 * (order=50). Appends each user-assistant interaction to today's note file for
 * long-term context retention. Runs after tool execution and before response
 * routing in the pipeline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryPersistSystem implements AgentSystem {

    private final MemoryComponent memoryComponent;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    @Override
    public String getName() {
        return "MemoryPersistSystem";
    }

    @Override
    public int getOrder() {
        return 50; // After tool execution
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        return context.isFinalAnswerReady();
    }

    @Override
    public AgentContext process(AgentContext context) {
        // Get the last user message
        Message lastUserMessage = getLastUserMessage(context);
        if (lastUserMessage == null) {
            return context;
        }

        // Get the LLM response
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response == null || response.getContent() == null) {
            return context;
        }

        // Build memory entry
        String entry = buildMemoryEntry(lastUserMessage, response);

        // Append to today's notes
        try {
            memoryComponent.appendToday(entry);
            log.debug("Persisted conversation to memory");
        } catch (Exception e) {
            log.warn("Failed to persist to memory", e);
        }

        return context;
    }

    private Message getLastUserMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }

        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage()) {
                return msg;
            }
        }
        return null;
    }

    private String buildMemoryEntry(Message userMessage, LlmResponse response) {
        String time = TIME_FORMATTER.format(Instant.now());
        String userContent = truncate(userMessage.getContent(), 200);
        String assistantContent = truncate(response.getContent(), 300);

        return String.format("[%s] User: %s | Assistant: %s%n",
                time, userContent, assistantContent);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        text = text.replace("\n", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
