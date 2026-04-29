package me.golemcore.bot.domain.context.resolution;

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
import me.golemcore.bot.domain.context.ContextResolver;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillTransitionRequest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the active skill for the current agent turn.
 *
 * <p>
 * Skill resolution follows a priority chain:
 * <ol>
 * <li><b>Explicit transition</b> — a {@link SkillTransitionRequest} from
 * {@code SkillTransitionTool} or {@code SkillPipelineSystem}</li>
 * <li><b>Context attribute</b> — {@code ACTIVE_SKILL_NAME} set by message
 * metadata or upstream logic</li>
 * <li><b>Session state (sticky)</b> — persisted skill name from previous turns
 * in the same session</li>
 * </ol>
 *
 * <p>
 * Once resolved, the active skill is persisted to session metadata so that
 * subsequent turns continue with the same skill ("sticky activation"). If the
 * resolved skill is not found or unavailable, the persisted state is cleared.
 */
@Slf4j
public class SkillResolver implements ContextResolver {

    private final SkillComponent skillComponent;

    public SkillResolver(SkillComponent skillComponent) {
        this.skillComponent = skillComponent;
    }

    /**
     * Resolves the active skill for the given context and persists the selection to
     * session metadata.
     *
     * @param context
     *            the current agent context
     */
    public void resolve(AgentContext context) {
        if (context == null) {
            return;
        }

        SkillTransitionRequest transition = context.getSkillTransitionRequest();
        String transitionTarget = transition != null ? transition.targetSkill() : null;
        if (transitionTarget != null) {
            applyActiveSkillByName(context, transitionTarget, formatActiveSkillSource(transition));
            context.clearSkillTransitionRequest();
        }

        resolveStickyActiveSkill(context);
    }

    private void resolveStickyActiveSkill(AgentContext context) {
        if (context.getActiveSkill() != null) {
            persistActiveSkillState(context);
            return;
        }

        String explicitSkillName = context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME);
        if (explicitSkillName != null && !explicitSkillName.isBlank()
                && applyActiveSkillByName(context, explicitSkillName, resolveExplicitSkillSource(context))) {
            return;
        }

        String sessionSkillName = readSessionActiveSkillName(context);
        if (sessionSkillName != null && !sessionSkillName.isBlank()) {
            if (applyActiveSkillByName(context, sessionSkillName, "session_state")) {
                return;
            }
            clearPersistedActiveSkill(context);
        }
    }

    private boolean applyActiveSkillByName(AgentContext context, String skillName, String source) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }

        Optional<Skill> skill = skillComponent.findByName(skillName);
        if (skill.isEmpty()) {
            log.warn("[SkillResolver] Active skill '{}' not found", skillName);
            return false;
        }
        if (!skill.get().isAvailable()) {
            log.warn("[SkillResolver] Active skill '{}' is unavailable", skillName);
            return false;
        }

        context.setActiveSkill(skill.get());
        context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, skill.get().getName());
        if (source != null && !source.isBlank()) {
            context.setAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE, source);
        }
        persistActiveSkillState(context);
        log.info("[SkillResolver] Skill transition: → {} ({})", skill.get().getName(), source);
        return true;
    }

    private void persistActiveSkillState(AgentContext context) {
        if (context.getSession() == null || context.getActiveSkill() == null
                || context.getActiveSkill().getName() == null || context.getActiveSkill().getName().isBlank()) {
            return;
        }
        if (context.getSession().getMetadata() == null) {
            context.getSession().setMetadata(new LinkedHashMap<>());
        }
        context.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, context.getActiveSkill().getName());
    }

    private void clearPersistedActiveSkill(AgentContext context) {
        if (context.getSession() == null || context.getSession().getMetadata() == null) {
            return;
        }
        context.getSession().getMetadata().remove(ContextAttributes.ACTIVE_SKILL_NAME);
    }

    private String readSessionActiveSkillName(AgentContext context) {
        if (context.getSession() == null || context.getSession().getMetadata() == null) {
            return null;
        }
        Object value = context.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME);
        if (value instanceof String skillName && !skillName.isBlank()) {
            return skillName;
        }
        return null;
    }

    private String resolveExplicitSkillSource(AgentContext context) {
        String existing = context.getAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return "message_metadata";
    }

    private String formatActiveSkillSource(SkillTransitionRequest transition) {
        if (transition == null || transition.reason() == null) {
            return null;
        }
        return transition.reason().name().toLowerCase(Locale.ROOT);
    }
}
