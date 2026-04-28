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
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.context.compaction.CompactionOrchestrationService;
import me.golemcore.bot.domain.context.compaction.CompactionPayloadMapper;
import me.golemcore.bot.domain.context.compaction.ContextCompactionPolicy;
import me.golemcore.bot.domain.context.compaction.ContextTokenEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * System for automatic conversation history compaction when context size
 * exceeds thresholds (order=18). Runs before ContextBuildingSystem to ensure
 * manageable context window. Estimates token count from character length, then
 * compares it against either an absolute token threshold or a model-aware ratio
 * of the resolved model context window before invoking
 * {@link CompactionOrchestrationService} for split-safe compaction with
 * structured details.
 */
@Component
@Slf4j
public class AutoCompactionSystem implements AgentSystem {

    private final CompactionOrchestrationService compactionOrchestrationService;
    private final ContextTokenEstimator contextTokenEstimator;
    private final ContextCompactionPolicy contextCompactionPolicy;

    public AutoCompactionSystem(
            CompactionOrchestrationService compactionOrchestrationService,
            ContextTokenEstimator contextTokenEstimator,
            ContextCompactionPolicy contextCompactionPolicy) {
        this.compactionOrchestrationService = compactionOrchestrationService;
        this.contextTokenEstimator = contextTokenEstimator;
        this.contextCompactionPolicy = contextCompactionPolicy;
    }

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
        return contextCompactionPolicy.isCompactionEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        return context.getMessages() != null && !context.getMessages().isEmpty();
    }

    @Override
    public AgentContext process(AgentContext context) {
        java.util.List<Message> messages = context.getMessages();

        int estimatedTokens = contextTokenEstimator.estimateMessages(messages);
        int threshold = contextCompactionPolicy.resolveHistoryThreshold(context);

        if (estimatedTokens <= threshold) {
            return context;
        }

        log.info("[AutoCompact] Context too large: ~{} tokens (threshold {}), {} messages. Compacting...",
                estimatedTokens, threshold, messages.size());

        int keepLast = contextCompactionPolicy.resolveCompactionKeepLast();
        CompactionResult result = compactionOrchestrationService.compact(
                context.getSession().getId(),
                CompactionReason.AUTO_THRESHOLD,
                keepLast);

        CompactionPayloadMapper.publishToContext(context, result);

        if (result.removed() > 0) {
            context.setMessages(new ArrayList<>(context.getSession().getMessages()));
            log.info("[AutoCompact] Compacted: removed {}, usedSummary={}", result.removed(), result.usedSummary());
        } else {
            log.debug("[AutoCompact] No messages compacted");
        }

        return context;
    }
}
