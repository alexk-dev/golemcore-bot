package me.golemcore.bot.domain.context.layer;

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

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.service.SkillTemplateEngine;

import java.util.Map;

/**
 * Injects skill context into the system prompt using progressive disclosure.
 *
 * <p>
 * If an active skill is selected, renders its full content (with template
 * variable substitution) and optional pipeline transition info. Otherwise,
 * renders a summary of all available skills to guide the LLM toward activating
 * the right one.
 *
 * <p>
 * This implements the "progressive disclosure" pattern: the LLM sees
 * lightweight skill descriptions by default, and only gets full instructions
 * when a skill is explicitly activated.
 */
@Slf4j
public class SkillLayer extends AbstractContextLayer {

    private final SkillComponent skillComponent;
    private final SkillTemplateEngine templateEngine;

    public SkillLayer(SkillComponent skillComponent, SkillTemplateEngine templateEngine) {
        super("skill", 40, 80, ContextLayerLifecycle.SESSION, 6_000);
        this.skillComponent = skillComponent;
        this.templateEngine = templateEngine;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return true;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        if (context.getActiveSkill() != null) {
            return assembleActiveSkill(context);
        }
        return assembleSkillsSummary(context);
    }

    private ContextLayerResult assembleActiveSkill(AgentContext context) {
        Skill skill = context.getActiveSkill();
        StringBuilder sb = new StringBuilder();

        sb.append("# Active Skill: ").append(skill.getName()).append("\n");

        String skillContent = skill.getContent();
        Map<String, String> vars = skill.getResolvedVariables();
        if (vars != null && !vars.isEmpty()) {
            skillContent = templateEngine.render(skillContent, vars);
        }
        sb.append(skillContent);

        if (skill.hasPipeline()) {
            sb.append("\n\n# Skill Pipeline\n");
            sb.append("You can transition to the next skill using the skill_transition tool.\n");
            if (skill.getNextSkill() != null) {
                sb.append("Default next: ").append(skill.getNextSkill()).append("\n");
            }
            Map<String, String> conditional = skill.getConditionalNextSkills();
            if (conditional != null && !conditional.isEmpty()) {
                sb.append("Conditional transitions:\n");
                for (Map.Entry<String, String> entry : conditional.entrySet()) {
                    sb.append("- ").append(entry.getKey()).append(" → ").append(entry.getValue()).append("\n");
                }
            }
        }

        String content = sb.toString();
        return result(content);
    }

    private ContextLayerResult assembleSkillsSummary(AgentContext context) {
        String summary = skillComponent.getSkillsSummary();
        context.setSkillsSummary(summary);

        if (summary == null || summary.isBlank()) {
            return empty();
        }

        String content = "# Available Skills\n" + summary
                + "If one of the available skills clearly matches the user's request, "
                + "call the skill_transition tool before doing the work.\n"
                + "Stay in the base prompt only when no listed skill is a better fit.\n";

        return result(content);
    }
}
