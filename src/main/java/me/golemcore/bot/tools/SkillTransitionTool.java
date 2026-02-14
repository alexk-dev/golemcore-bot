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

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.ContextAttributes;

/**
 * Tool for explicit skill transitions within a pipeline.
 *
 * <p>
 * The LLM calls this tool to explicitly switch to a different skill
 * mid-conversation. This enables skill pipelines where the agent can
 * orchestrate complex workflows across multiple specialized skills.
 *
 * <p>
 * Uses {@link AgentContextHolder} to modify the current {@link AgentContext}
 * and set the next skill.
 * {@link me.golemcore.bot.domain.system.SkillPipelineSystem} detects the
 * transition and loads the target skill in the next iteration.
 *
 * <p>
 * Always enabled.
 *
 * @see me.golemcore.bot.domain.loop.AgentContextHolder
 * @see me.golemcore.bot.domain.system.SkillPipelineSystem
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillTransitionTool implements ToolComponent {

    private final SkillComponent skillComponent;
    private final BotProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "skill_transition";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("skill_transition")
                .description("Transition to a different skill in the pipeline. " +
                        "Use this when the current task requires capabilities from another skill.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "target_skill", Map.of(
                                        "type", "string",
                                        "description", "Name of the skill to transition to"),
                                "reason", Map.of(
                                        "type", "string",
                                        "description", "Brief reason for the transition")),
                        "required", java.util.List.of("target_skill")))
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        // Must run on calling thread to access AgentContextHolder (ThreadLocal)
        String targetSkill = (String) parameters.get("target_skill");
        String reason = (String) parameters.get("reason");

        if (targetSkill == null || targetSkill.isBlank()) {
            return CompletableFuture.completedFuture(ToolResult.failure("target_skill is required"));
        }

        Optional<Skill> skill = skillComponent.findByName(targetSkill);
        if (skill.isEmpty()) {
            return CompletableFuture.completedFuture(ToolResult.failure("Skill not found: " + targetSkill));
        }

        if (!skill.get().isAvailable()) {
            return CompletableFuture.completedFuture(ToolResult.failure("Skill is unavailable: " + targetSkill));
        }

        // Store transition target in AgentContext via ThreadLocal
        AgentContext context = AgentContextHolder.get();
        if (context == null) {
            return CompletableFuture.completedFuture(ToolResult.failure("No agent context available"));
        }

        context.setSkillTransitionRequest(me.golemcore.bot.domain.model.SkillTransitionRequest.explicit(targetSkill));

        log.info("[SkillTransition] Transitioning to skill '{}' (reason: {})", targetSkill, reason);

        return CompletableFuture.completedFuture(ToolResult.success("Transitioning to skill: " + targetSkill +
                (reason != null ? " (" + reason + ")" : "")));
    }

    @Override
    public boolean isEnabled() {
        return properties.getTools().getSkillTransition().isEnabled();
    }
}
