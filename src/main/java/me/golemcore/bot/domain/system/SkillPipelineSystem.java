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
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * System for automatic skill pipeline transitions when active skill declares
 * nextSkill (order=55). Triggers when LLM finishes (no tool calls) and active
 * skill has nextSkill or conditional_next_skills in YAML frontmatter. Prevents
 * infinite loops via MAX_PIPELINE_DEPTH tracking. Runs after MemoryPersist,
 * before ResponseRouting. Complements explicit transitions via
 * SkillTransitionTool.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillPipelineSystem implements AgentSystem {

    private static final int MAX_PIPELINE_DEPTH = 5;
    private static final String PIPELINE_DEPTH_KEY = "skill.pipeline.depth";

    private final SkillComponent skillComponent;

    @Override
    public String getName() {
        return "SkillPipelineSystem";
    }

    @Override
    public int getOrder() {
        return 55;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        // Only process if:
        // 1. There's a final LLM response (no pending tool calls)
        // 2. Active skill has a nextSkill
        // 3. No explicit transition already set (by SkillTransitionTool)
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response == null || response.hasToolCalls()) {
            return false;
        }

        Skill activeSkill = context.getActiveSkill();
        if (activeSkill == null || activeSkill.getNextSkill() == null) {
            return false;
        }

        String transitionTarget = context.getAttribute(ContextAttributes.SKILL_TRANSITION_TARGET);
        if (transitionTarget != null) {
            return false; // explicit transition already set
        }

        return true;
    }

    @Override
    public AgentContext process(AgentContext context) {
        Skill activeSkill = context.getActiveSkill();
        String nextSkillName = activeSkill.getNextSkill();

        // Check pipeline depth to prevent infinite loops
        Integer depth = context.getAttribute(PIPELINE_DEPTH_KEY);
        if (depth == null)
            depth = 0;

        if (depth >= MAX_PIPELINE_DEPTH) {
            log.warn("[Pipeline] Max pipeline depth ({}) reached, stopping", MAX_PIPELINE_DEPTH);
            return context;
        }

        // Verify next skill exists
        var nextSkill = skillComponent.findByName(nextSkillName);
        if (nextSkill.isEmpty()) {
            log.warn("[Pipeline] Next skill '{}' not found, stopping pipeline", nextSkillName);
            return context;
        }

        if (!nextSkill.get().isAvailable()) {
            log.warn("[Pipeline] Next skill '{}' unavailable, stopping pipeline", nextSkillName);
            return context;
        }

        log.info("[Pipeline] Auto-transitioning: {} → {} (depth: {})",
                activeSkill.getName(), nextSkillName, depth + 1);

        // Store current response as intermediate in session
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
            context.getSession().addMessage(Message.builder()
                    .role("assistant")
                    .content(response.getContent())
                    .timestamp(Instant.now())
                    .build());
        }

        // Set transition target for ContextBuildingSystem to pick up
        context.setAttribute(ContextAttributes.SKILL_TRANSITION_TARGET, nextSkillName);
        context.setAttribute(PIPELINE_DEPTH_KEY, depth + 1);

        // Force loop continuation by marking tools as executed and clearing response
        context.setAttribute(ContextAttributes.TOOLS_EXECUTED, true);
        context.setAttribute(ContextAttributes.LLM_RESPONSE, null);
        context.setAttribute(ContextAttributes.LLM_TOOL_CALLS, null);

        return context;
    }
}
