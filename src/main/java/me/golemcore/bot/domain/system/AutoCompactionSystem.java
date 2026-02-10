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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.CompactionService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.infrastructure.config.ModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * System for automatic conversation history compaction when context size
 * exceeds thresholds (order=18). Runs before ContextBuildingSystem to ensure
 * manageable context window. Estimates token count from character length, uses
 * model's maxInputTokens from models.json (with 80% safety margin), and invokes
 * {@link service.CompactionService} for LLM-powered summarization with fallback
 * to simple truncation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoCompactionSystem implements AgentSystem {

    private final SessionPort sessionService;
    private final CompactionService compactionService;
    private final BotProperties properties;
    private final ModelConfigService modelConfigService;

    @Override
    public String getName() {
        return "AutoCompactionSystem";
    }

    @Override
    public int getOrder() {
        return 18;
    }

    @Override
    public boolean isEnabled() {
        return properties.getAutoCompact().isEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        return context.getMessages() != null && !context.getMessages().isEmpty();
    }

    @Override
    public AgentContext process(AgentContext context) {
        var config = properties.getAutoCompact();
        List<Message> messages = context.getMessages();

        long totalChars = messages.stream()
                .mapToLong(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();
        int estimatedTokens = (int) (totalChars / config.getCharsPerToken()) + config.getSystemPromptOverheadTokens();

        // Use model's maxInputTokens from models.json if available, with 80% safety
        // margin.
        // Fall back to config's maxContextTokens.
        int threshold = resolveMaxTokens(context, config);

        if (estimatedTokens <= threshold) {
            return context;
        }

        log.info("[AutoCompact] Context too large: ~{} tokens (threshold {}), {} messages. Compacting...",
                estimatedTokens, threshold, messages.size());

        String sessionId = context.getSession().getId();
        int keepLast = config.getKeepLastMessages();

        List<Message> messagesToCompact = sessionService.getMessagesToCompact(sessionId, keepLast);
        if (messagesToCompact.isEmpty()) {
            log.debug("[AutoCompact] No messages to compact (all within keepLast={})", keepLast);
            return context;
        }

        String summary = compactionService.summarize(messagesToCompact);

        int removed;
        if (summary != null) {
            Message summaryMessage = compactionService.createSummaryMessage(summary);
            removed = sessionService.compactWithSummary(sessionId, keepLast, summaryMessage);
            log.info("[AutoCompact] Compacted with LLM summary: removed {} messages, kept {}", removed, keepLast);
        } else {
            removed = sessionService.compactMessages(sessionId, keepLast);
            log.info("[AutoCompact] Compacted with simple truncation (LLM unavailable): removed {} messages, kept {}",
                    removed, keepLast);
        }

        if (removed > 0) {
            context.setMessages(new ArrayList<>(context.getSession().getMessages()));
        }

        return context;
    }

    /**
     * Resolve the max context token threshold. Uses model's maxInputTokens from
     * models.json (with 80% safety margin), capped by config's maxContextTokens as
     * upper bound.
     */
    private int resolveMaxTokens(AgentContext context, BotProperties.AutoCompactProperties config) {
        try {
            String modelTier = context.getModelTier();
            String modelName = resolveModelName(modelTier);
            if (modelName != null) {
                int modelMax = modelConfigService.getMaxInputTokens(modelName);
                int modelThreshold = (int) (modelMax * 0.8);
                return Math.min(modelThreshold, config.getMaxContextTokens());
            }
        } catch (Exception e) {
            log.debug("[AutoCompact] Failed to resolve model max tokens, using config default", e);
        }
        return config.getMaxContextTokens();
    }

    private String resolveModelName(String tier) {
        var router = properties.getRouter();
        return switch (tier != null ? tier : "balanced") {
        case "deep" -> router.getDeepModel();
        case "coding" -> router.getCodingModel();
        case "smart" -> router.getSmartModel();
        default -> router.getDefaultModel();
        };
    }
}
