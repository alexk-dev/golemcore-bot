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

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.LlmPort;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Service for conversation history compaction using LLM-powered summarization.
 * Replaces old messages with a concise summary to manage context window limits
 * while preserving essential conversation context. Used by both manual /compact
 * commands and automatic compaction via
 * {@link domain.system.AutoCompactionSystem}. Falls back to simple truncation
 * if LLM is unavailable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompactionService {

    private final LlmPort llmPort;
    private final BotProperties properties;
    private final Clock clock;

    private static final long SUMMARY_TIMEOUT_MS = 15_000;
    private static final int MAX_SUMMARY_TOKENS = 500;

    /**
     * Continuation-oriented summary prompt optimized for resuming work after compaction.
     *
     * Goal: produce a "working memory" recap that helps the assistant continue work seamlessly
     * after compaction (what we did, what we're doing, what to do next).
     */
    private static final String SYSTEM_PROMPT = """
            Provide a detailed but concise summary of the conversation above.
            Focus on information that would be helpful for continuing the conversation.

            Include, when applicable:
            - what we did / what has been accomplished
            - what we're doing right now
            - decisions made and their rationale
            - user preferences and constraints
            - important details such as referenced files, commands, settings, IDs/URLs
            - open questions, blockers, and what we should do next

            Keep it factual. Write in the same language the conversation uses.
            Do NOT include greetings, apologies, or meta-commentary. Output only the summary.""";

    /**
     * Summarize a list of messages into a single summary text.
     *
     * @return summary text, or null if LLM is unavailable
     */
    public String summarize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        if (llmPort == null || !llmPort.isAvailable()) {
            log.warn("[Compaction] LLM not available, cannot summarize");
            return null;
        }

        String conversation = formatConversation(messages);

        LlmRequest request = LlmRequest.builder()
                .model(properties.getRouter().getBalancedModel())
                .reasoningEffort(properties.getRouter().getBalancedModelReasoning())
                .systemPrompt(SYSTEM_PROMPT)
                .messages(List.of(Message.builder()
                        .role("user")
                        // Continuation-oriented prompt: preserve enough context to resume work after compaction.
                        .content("Provide a detailed but concise summary of our conversation above. "
                                + "Focus on information that would be helpful for continuing the conversation, "
                                + "including what we did, what we're doing, which files we're working on, and what we're going to do next.\n\n"
                                + conversation)
                        .build()))
                .maxTokens(MAX_SUMMARY_TOKENS)
                .temperature(0.3)
                .build();

        try {
            long start = clock.millis();
            LlmResponse response = llmPort.chat(request).get(SUMMARY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long elapsed = clock.millis() - start;

            String summary = response.getContent();
            if (summary == null || summary.isBlank()) {
                log.warn("[Compaction] LLM returned empty summary");
                return null;
            }
            log.info("[Compaction] Summarized {} messages in {}ms ({} chars)",
                    messages.size(), elapsed, summary.length());
            return summary;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Compaction] LLM summarization interrupted: {}", e.getMessage());
            return null;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("[Compaction] LLM summarization failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a system message containing the summary.
     */
    public Message createSummaryMessage(String summary) {
        return Message.builder()
                .role("system")
                .content("[Conversation summary]\n" + summary)
                .timestamp(clock.instant())
                .build();
    }

    private String formatConversation(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .filter(m -> !m.isToolMessage()) // skip tool call results for cleaner summary
                .map(m -> m.getRole() + ": " + truncate(m.getContent(), 300))
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }
}
