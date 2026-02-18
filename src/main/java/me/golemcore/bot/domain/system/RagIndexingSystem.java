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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.RagPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * System for indexing conversation exchanges into LightRAG for long-term
 * semantic memory (order=55). Runs after MemoryPersistSystem, before
 * ResponseRoutingSystem. Uses fire-and-forget async indexing to avoid blocking
 * the response pipeline. Filters trivial exchanges (greetings, short messages).
 * Integrates with external LightRAG REST API via {@link port.outbound.RagPort}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagIndexingSystem implements AgentSystem {

    private final RagPort ragPort;
    private final RuntimeConfigService runtimeConfigService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final Set<String> TRIVIAL_PATTERNS = Set.of(
            "hi", "hello", "hey", "bye", "thanks", "thank you", "ok", "okay",
            "yes", "no", "привет", "пока", "спасибо", "да", "нет");

    @Override
    public String getName() {
        return "RagIndexingSystem";
    }

    @Override
    public int getOrder() {
        return 55; // After MemoryPersistSystem (50)
    }

    @Override
    public boolean isEnabled() {
        return ragPort.isAvailable();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // Prefer TurnOutcome; fall back to legacy finalAnswerReady
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null) {
            return outcome.getAssistantText() != null && !outcome.getAssistantText().isBlank();
        }
        return Boolean.TRUE.equals(context.getAttribute(ContextAttributes.FINAL_ANSWER_READY));
    }

    @Override
    public AgentContext process(AgentContext context) {
        Message lastUserMessage = getLastUserMessage(context);
        if (lastUserMessage == null) {
            return context;
        }

        // Prefer TurnOutcome.assistantText; fall back to legacy LLM_RESPONSE
        TurnOutcome outcome = context.getTurnOutcome();
        String assistantText;
        if (outcome != null && outcome.getAssistantText() != null) {
            assistantText = outcome.getAssistantText();
        } else {
            LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
            if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                return context;
            }
            assistantText = response.getContent();
        }

        String userText = lastUserMessage.getContent();

        // Skip trivial exchanges
        if (isTrivial(userText, assistantText)) {
            log.debug("[RagIndexing] Skipping trivial exchange");
            return context;
        }

        // Format and index (fire-and-forget)
        String document = formatDocument(userText, assistantText, context);
        ragPort.index(document).whenComplete((v, ex) -> {
            if (ex != null) {
                log.warn("[RagIndexing] Failed to index: {}", ex.getMessage());
            } else {
                log.debug("[RagIndexing] Indexed {} chars", document.length());
            }
        });

        return context;
    }

    private boolean isTrivial(String userText, String assistantText) {
        int minLength = runtimeConfigService.getRagIndexMinLength();
        int combinedLength = userText.length() + assistantText.length();
        if (combinedLength < minLength) {
            return true;
        }

        String normalized = userText.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[!?.,:;]+$", "");
        return TRIVIAL_PATTERNS.contains(normalized);
    }

    String formatDocument(String userText, String assistantText, AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Date: ").append(DATE_FORMATTER.format(Instant.now())).append("\n");

        if (context.getActiveSkill() != null) {
            sb.append("Skill: ").append(context.getActiveSkill().getName()).append("\n");
        }

        sb.append("User: ").append(userText.trim()).append("\n");
        sb.append("Assistant: ").append(assistantText.trim()).append("\n");
        return sb.toString();
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
}
