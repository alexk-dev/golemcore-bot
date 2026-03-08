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
import me.golemcore.bot.domain.model.CompactionReason;
import me.golemcore.bot.domain.model.CompactionResult;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.CompactionOrchestrationService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

/**
 * System for automatic conversation history compaction when context size
 * exceeds thresholds (order=18). Runs before ContextBuildingSystem to ensure
 * manageable context window. Estimates token count from character length, uses
 * model's maxInputTokens from models.json (with 80% safety margin), and invokes
 * {@link CompactionOrchestrationService} for split-safe compaction with
 * structured details.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoCompactionSystem implements AgentSystem {

    private final CompactionOrchestrationService compactionOrchestrationService;
    private final RuntimeConfigService runtimeConfigService;
    private final ModelSelectionService modelSelectionService;

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
        return runtimeConfigService.isCompactionEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        return context.getMessages() != null && !context.getMessages().isEmpty();
    }

    @Override
    public AgentContext process(AgentContext context) {
        java.util.List<Message> messages = context.getMessages();

        long totalChars = messages.stream()
                .mapToLong(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();
        int estimatedTokens = (int) (totalChars / 3.5) + 8000;

        int threshold = resolveMaxTokens(context);

        if (estimatedTokens <= threshold) {
            return context;
        }

        log.info("[AutoCompact] Context too large: ~{} tokens (threshold {}), {} messages. Compacting...",
                estimatedTokens, threshold, messages.size());

        int keepLast = runtimeConfigService.getCompactionKeepLastMessages();
        CompactionResult result = compactionOrchestrationService.compact(
                context.getSession().getId(),
                CompactionReason.AUTO_THRESHOLD,
                keepLast);

        if (result.details() != null) {
            context.setAttribute(ContextAttributes.COMPACTION_LAST_DETAILS, toDetailsPayload(result));
        }

        if (result.removed() > 0) {
            context.setMessages(new ArrayList<>(context.getSession().getMessages()));
            log.info("[AutoCompact] Compacted: removed {}, usedSummary={}", result.removed(), result.usedSummary());
        } else {
            log.debug("[AutoCompact] No messages compacted");
        }

        return context;
    }

    /**
     * Resolve the max context token threshold. Uses model's maxInputTokens via
     * ModelSelectionService (with 80% safety margin), capped by config's
     * maxContextTokens as upper bound.
     */
    private int resolveMaxTokens(AgentContext context) {
        try {
            int modelMax = modelSelectionService.resolveMaxInputTokens(context.getModelTier());
            int modelThreshold = (int) (modelMax * 0.8);
            return Math.min(modelThreshold, runtimeConfigService.getCompactionMaxContextTokens());
        } catch (Exception e) {
            log.debug("[AutoCompact] Failed to resolve model max tokens, using config default", e);
        }
        return runtimeConfigService.getCompactionMaxContextTokens();
    }

    private Map<String, Object> toDetailsPayload(CompactionResult result) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("removed", result.removed());
        payload.put("usedSummary", result.usedSummary());
        if (result.details() != null) {
            payload.put("reason", result.details().reason() != null ? result.details().reason().name() : null);
            payload.put("summarizedCount", result.details().summarizedCount());
            payload.put("keptCount", result.details().keptCount());
            payload.put("splitTurnDetected", result.details().splitTurnDetected());
            payload.put("summaryLength", result.details().summaryLength());
            payload.put("durationMs", result.details().durationMs());
            payload.put("toolCount", result.details().toolCount());
            payload.put("readFilesCount", result.details().readFilesCount());
            payload.put("modifiedFilesCount", result.details().modifiedFilesCount());
            payload.put("fileChanges", result.details().fileChanges());
        }
        return payload;
    }
}
