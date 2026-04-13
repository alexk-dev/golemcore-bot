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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionModelSettingsSupport;
import me.golemcore.bot.domain.service.UserPreferencesService;

import java.util.Optional;

/**
 * Resolves the model tier for the current agent turn.
 *
 * <p>
 * Tier resolution follows a priority chain that balances user control, skill
 * recommendations, and system defaults:
 * <ol>
 * <li><b>Forced user preference</b> — user explicitly locked a tier via
 * preferences with {@code tierForce=true}</li>
 * <li><b>Reflection override</b> — autonomous recovery runs may specify a
 * dedicated reflection tier</li>
 * <li><b>Active skill recommendation</b> — the skill's YAML frontmatter
 * {@code model_tier} field</li>
 * <li><b>User preference (non-forced)</b> — soft preference from user
 * settings</li>
 * <li><b>Implicit default</b> — {@code null}, which downstream LLM execution
 * treats as "balanced"</li>
 * </ol>
 *
 * <p>
 * Resolution only runs on iteration 0 (first pass). The
 * {@code DynamicTierSystem} handles mid-conversation upgrades on subsequent
 * iterations.
 *
 * <p>
 * After resolution, tier metadata ({@code MODEL_TIER_SOURCE},
 * {@code MODEL_TIER_MODEL_ID}, {@code MODEL_TIER_REASONING}) is published to
 * {@link ContextAttributes} for observability.
 */
@RequiredArgsConstructor
@Slf4j
public class TierResolver {

    private final UserPreferencesService userPreferencesService;
    private final ModelSelectionService modelSelectionService;
    private final RuntimeConfigService runtimeConfigService;
    private final SkillComponent skillComponent;

    /**
     * Resolves the model tier for the given context and publishes tier metadata to
     * {@link ContextAttributes}.
     *
     * @param context
     *            the current agent context
     */
    public void resolve(AgentContext context) {
        if (context == null) {
            return;
        }

        UserPreferences prefs = userPreferencesService.getPreferences();
        resolveTier(context, prefs);

        if (isAutoModeMessage(context) && context.getModelTier() == null) {
            applyModelTier(context, runtimeConfigService.getAutoModelTier(), "auto_mode_default");
        }

        ensureResolvedTierMetadata(context);
    }

    private void resolveTier(AgentContext context, UserPreferences prefs) {
        if (context.getCurrentIteration() != 0) {
            return;
        }

        TierPreference tierPreference = resolveTierPreference(context, prefs);

        if (tierPreference.force() && tierPreference.tier() != null) {
            applyModelTier(context, tierPreference.tier(), tierPreference.forcedSource());
            return;
        }

        String webhookTier = context.getAttribute(ContextAttributes.WEBHOOK_MODEL_TIER);
        if (webhookTier != null && !webhookTier.isBlank()) {
            applyModelTier(context, webhookTier, "webhook");
            return;
        }

        if (isAutoReflectionContext(context)) {
            resolveReflectionTier(context, tierPreference.tier(), tierPreference.source());
            return;
        }

        Skill activeSkill = context.getActiveSkill();
        if (activeSkill != null && activeSkill.getModelTier() != null) {
            applyModelTier(context, activeSkill.getModelTier(), "skill");
        } else if (tierPreference.tier() != null) {
            applyModelTier(context, tierPreference.tier(), tierPreference.source());
        }
    }

    private TierPreference resolveTierPreference(AgentContext context, UserPreferences prefs) {
        if (context.getSession() != null
                && SessionModelSettingsSupport.hasModelSettings(context.getSession())) {
            return new TierPreference(
                    SessionModelSettingsSupport.readModelTier(context.getSession()),
                    SessionModelSettingsSupport.readForce(context.getSession()),
                    "session_pref",
                    "session_pref_forced");
        }
        return new TierPreference(prefs.getModelTier(), prefs.isTierForce(), "user_pref", "user_pref_forced");
    }

    private void resolveReflectionTier(AgentContext context, String fallbackTier, String fallbackSource) {
        Skill activeSkill = resolveReflectionSkill(context);
        String configuredReflectionTier = resolveReflectionTierOverride(context);
        boolean priority = resolveReflectionTierPriority(context);
        String skillReflectionTier = activeSkill != null ? activeSkill.getReflectionTier() : null;

        if (priority && configuredReflectionTier != null && !configuredReflectionTier.isBlank()) {
            applyModelTier(context, configuredReflectionTier, "reflection_override");
            return;
        }
        if (skillReflectionTier != null && !skillReflectionTier.isBlank()) {
            applyModelTier(context, skillReflectionTier, "skill_reflection");
            return;
        }
        if (configuredReflectionTier != null && !configuredReflectionTier.isBlank()) {
            applyModelTier(context, configuredReflectionTier, "reflection_override");
            return;
        }

        String runtimeReflectionTier = runtimeConfigService.getAutoReflectionModelTier();
        if (runtimeReflectionTier != null && !runtimeReflectionTier.isBlank()) {
            applyModelTier(context, runtimeReflectionTier, "runtime_reflection");
            return;
        }
        if (activeSkill != null && activeSkill.getModelTier() != null) {
            applyModelTier(context, activeSkill.getModelTier(), "skill");
            return;
        }
        if (fallbackTier != null) {
            applyModelTier(context, fallbackTier, fallbackSource);
        }
    }

    private void applyModelTier(AgentContext context, String tier, String source) {
        context.setModelTier(tier);
        if (source != null && !source.isBlank()) {
            context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, source);
        }
        updateResolvedTierMetadata(context);
    }

    private void ensureResolvedTierMetadata(AgentContext context) {
        if (context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE) == null) {
            context.setAttribute(ContextAttributes.MODEL_TIER_SOURCE, "implicit_default");
        }
        updateResolvedTierMetadata(context);
    }

    private void updateResolvedTierMetadata(AgentContext context) {
        try {
            ModelSelectionService.ModelSelection selection = modelSelectionService
                    .resolveForTier(context.getModelTier());
            if (selection.model() != null && !selection.model().isBlank()) {
                context.setAttribute(ContextAttributes.MODEL_TIER_MODEL_ID, selection.model());
            }
            if (selection.reasoning() != null && !selection.reasoning().isBlank()) {
                context.setAttribute(ContextAttributes.MODEL_TIER_REASONING, selection.reasoning());
            } else if (context.getAttributes() != null) {
                context.getAttributes().remove(ContextAttributes.MODEL_TIER_REASONING);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (context.getAttributes() != null) {
                context.getAttributes().remove(ContextAttributes.MODEL_TIER_MODEL_ID);
                context.getAttributes().remove(ContextAttributes.MODEL_TIER_REASONING);
            }
        }
    }

    private boolean isAutoModeMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private boolean isAutoReflectionContext(AgentContext context) {
        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE))) {
            return true;
        }
        Message last = getLastMessage(context);
        return last != null && last.getMetadata() != null
                && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_REFLECTION_ACTIVE));
    }

    private String resolveReflectionTierOverride(AgentContext context) {
        String configured = context.getAttribute(ContextAttributes.AUTO_REFLECTION_TIER);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Message last = getLastMessage(context);
        return last != null ? AutoRunContextSupport.readMetadataString(last.getMetadata(),
                ContextAttributes.AUTO_REFLECTION_TIER) : null;
    }

    private Skill resolveReflectionSkill(AgentContext context) {
        String skillName = resolveReflectionSkillName(context);
        if (skillName != null && !skillName.isBlank()) {
            Optional<Skill> reflectedSkill = skillComponent.findByName(skillName);
            if (reflectedSkill.isPresent()) {
                return reflectedSkill.get();
            }
        }
        return context.getActiveSkill();
    }

    private String resolveReflectionSkillName(AgentContext context) {
        String explicit = context.getAttribute(ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        explicit = context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        Message last = getLastMessage(context);
        if (last == null || last.getMetadata() == null) {
            return null;
        }
        String runSkill = AutoRunContextSupport.readMetadataString(last.getMetadata(),
                ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        if (runSkill != null && !runSkill.isBlank()) {
            return runSkill;
        }
        return AutoRunContextSupport.readMetadataString(last.getMetadata(), ContextAttributes.ACTIVE_SKILL_NAME);
    }

    private boolean resolveReflectionTierPriority(AgentContext context) {
        Boolean priority = context.getAttribute(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
        if (priority != null) {
            return priority;
        }
        Message last = getLastMessage(context);
        if (last == null || last.getMetadata() == null) {
            return false;
        }
        Object value = last.getMetadata().get(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    private Message getLastMessage(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        return context.getMessages().get(context.getMessages().size() - 1);
    }

    private record TierPreference(String tier, boolean force, String source, String forcedSource) {
    }
}
