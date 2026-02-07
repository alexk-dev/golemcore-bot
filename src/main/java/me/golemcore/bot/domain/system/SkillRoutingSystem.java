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

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.routing.MessageContextAggregator;
import me.golemcore.bot.routing.SkillMatchResult;
import me.golemcore.bot.routing.SkillMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * System for intelligent skill routing using hybrid semantic + LLM
 * classification (order=15). Runs before ContextBuildingSystem to select the
 * appropriate skill for the request. Supports fragmented input detection via
 * {@link routing.MessageContextAggregator} and two-stage matching (embeddings
 * pre-filter + LLM classifier). Sets activeSkill and modelTier in the context
 * for downstream systems.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillRoutingSystem implements AgentSystem {

    private final SkillMatcher skillMatcher;
    private final SkillComponent skillComponent;
    private final BotProperties properties;
    private final MessageContextAggregator messageAggregator;

    @Override
    public String getName() {
        return "SkillRoutingSystem";
    }

    @Override
    public int getOrder() {
        return 15; // Before ContextBuildingSystem (20)
    }

    @Override
    public boolean isEnabled() {
        return properties.getRouter().getSkillMatcher().isEnabled();
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // Only run on first iteration
        if (context.getCurrentIteration() != 0)
            return false;
        // Skip routing for auto-mode synthetic messages
        if (isAutoModeMessage(context))
            return false;
        return true;
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty())
            return false;
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }

    @Override
    public AgentContext process(AgentContext context) {
        log.debug("[SkillRouting] Starting skill routing...");

        if (!skillMatcher.isEnabled()) {
            log.info("[SkillRouting] Skill matcher DISABLED");
            return context;
        }

        // Build routing query - handles fragmented input detection
        String routingQuery = messageAggregator.buildRoutingQuery(context.getMessages());
        if (routingQuery.isBlank()) {
            log.debug("[SkillRouting] No user message found, skipping routing");
            return context;
        }
        log.debug("[SkillRouting] Routing query: '{}'", truncate(routingQuery, 100));

        // Analyze fragmentation for logging
        MessageContextAggregator.AggregationAnalysis analysis = messageAggregator.analyze(context.getMessages());
        log.debug("[SkillRouting] Fragmentation analysis: fragmented={}, signals={}",
                analysis.isFragmented(), analysis.signals());

        if (analysis.isFragmented()) {
            context.setAttribute("routing.fragmented", true);
            context.setAttribute("routing.fragmentationSignals", analysis.signals());
        }

        // Get available skills
        List<Skill> availableSkills = skillComponent.getAvailableSkills();
        log.debug("[SkillRouting] Available skills: {} ({})",
                availableSkills.size(),
                availableSkills.stream().map(Skill::getName).toList());

        if (availableSkills.isEmpty()) {
            log.debug("[SkillRouting] No available skills, skipping routing");
            return context;
        }

        // Index skills if not ready
        if (!skillMatcher.isReady()) {
            log.debug("[SkillRouting] Skill matcher not ready, indexing skills...");
            skillMatcher.indexSkills(availableSkills);
        }

        try {
            log.debug("[SkillRouting] Running skill matcher...");
            // Run skill matching with aggregated query
            long routingTimeout = properties.getRouter().getSkillMatcher().getRoutingTimeoutMs();
            SkillMatchResult result = skillMatcher.match(
                    routingQuery,
                    context.getMessages(),
                    availableSkills).get(routingTimeout, TimeUnit.MILLISECONDS);

            // Store result in context
            context.setAttribute("routing.result", result);
            context.setAttribute("routing.skill", result.getSelectedSkill());
            context.setAttribute("routing.modelTier", result.getModelTier());
            context.setAttribute("routing.confidence", result.getConfidence());
            context.setAttribute("routing.query", routingQuery);

            // Set model tier from routing result
            if (result.getModelTier() != null) {
                context.setModelTier(result.getModelTier());
            }

            if (result.hasMatch()) {
                // Load full skill content
                Optional<Skill> skill = skillComponent.findByName(result.getSelectedSkill());
                if (skill.isPresent()) {
                    context.setAttribute("routing.skillContent", skill.get().getContent());
                    context.setActiveSkill(skill.get());
                    log.debug("[SkillRouting] Loaded skill content: {} chars",
                            skill.get().getContent() != null ? skill.get().getContent().length() : 0);
                } else {
                    log.warn("[SkillRouting] Could not find skill by name: {}", result.getSelectedSkill());
                }

                log.info(
                        "[SkillRouting] MATCHED: skill={}, confidence={}, tier={}, cached={}, llmUsed={}, latency={}ms",
                        result.getSelectedSkill(),
                        String.format("%.2f", result.getConfidence()),
                        result.getModelTier(),
                        result.isCached(),
                        result.isLlmClassifierUsed(),
                        result.getLatencyMs());
            } else {
                log.info("[SkillRouting] NO MATCH: reason={}, fragmented={}, latency={}ms",
                        result.getReason(),
                        analysis.isFragmented(),
                        result.getLatencyMs());
            }

        } catch (Exception e) { // NOSONAR - intentionally catch all to prevent routing failures from breaking
                                // the loop
            log.error("[SkillRouting] FAILED: {}", e.getMessage(), e);
            context.setAttribute("routing.error", e.getMessage());
        }

        return context;
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }
}
