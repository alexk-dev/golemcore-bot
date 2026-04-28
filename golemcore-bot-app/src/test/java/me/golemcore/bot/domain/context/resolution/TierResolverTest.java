package me.golemcore.bot.domain.context.resolution;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.model.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TierResolverTest {

    private UserPreferencesService userPreferencesService;
    private ModelSelectionService modelSelectionService;
    private RuntimeConfigService runtimeConfigService;
    private SkillComponent skillComponent;
    private TierResolver resolver;

    @BeforeEach
    void setUp() {
        userPreferencesService = mock(UserPreferencesService.class);
        modelSelectionService = mock(ModelSelectionService.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        skillComponent = mock(SkillComponent.class);

        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("gpt-5", "medium"));

        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().build());

        resolver = new TierResolver(userPreferencesService, modelSelectionService, runtimeConfigService,
                skillComponent);
    }

    @Test
    void shouldApplyForcedUserTier() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("power").tierForce(true).build());

        AgentContext context = AgentContext.builder().currentIteration(0).build();
        resolver.resolve(context);

        assertEquals("power", context.getModelTier());
        assertEquals("user_pref_forced", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldApplyForcedSessionTierBeforeUserPreferences() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("power").tierForce(true).build());
        AgentSession session = AgentSession.builder()
                .metadata(Map.of(
                        ContextAttributes.SESSION_MODEL_TIER, "coding",
                        ContextAttributes.SESSION_MODEL_TIER_FORCE, true))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .currentIteration(0)
                .build();
        resolver.resolve(context);

        assertEquals("coding", context.getModelTier());
        assertEquals("session_pref_forced", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldApplySkillTierWhenNotForced() {
        Skill skill = Skill.builder().name("test").description("Test").modelTier("reasoning").build();

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .activeSkill(skill)
                .build();
        resolver.resolve(context);

        assertEquals("reasoning", context.getModelTier());
        assertEquals("skill", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldApplyWebhookTierBeforeSkillAndUserPreferenceWhenNotForced() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("fast").build());
        Skill skill = Skill.builder().name("test").description("Test").modelTier("reasoning").build();

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .activeSkill(skill)
                .attributes(new HashMap<>(Map.of(ContextAttributes.WEBHOOK_MODEL_TIER, "coding")))
                .build();
        resolver.resolve(context);

        assertEquals("coding", context.getModelTier());
        assertEquals("webhook", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldApplyWebhookTierAboveForcedUserTier() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("power").tierForce(true).build());

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(ContextAttributes.WEBHOOK_MODEL_TIER, "special5")))
                .build();
        resolver.resolve(context);

        assertEquals("special5", context.getModelTier());
        assertEquals("webhook", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldApplyWebhookTierAboveForcedSessionTier() {
        AgentSession session = AgentSession.builder()
                .metadata(Map.of(
                        ContextAttributes.SESSION_MODEL_TIER, "balanced",
                        ContextAttributes.SESSION_MODEL_TIER_FORCE, true))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(ContextAttributes.WEBHOOK_MODEL_TIER, "special5")))
                .build();
        resolver.resolve(context);

        assertEquals("special5", context.getModelTier());
        assertEquals("webhook", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldFallBackToUserPrefWhenNoSkillTier() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("fast").build());

        AgentContext context = AgentContext.builder().currentIteration(0).build();
        resolver.resolve(context);

        assertEquals("fast", context.getModelTier());
        assertEquals("user_pref", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldFallBackToSessionTierWhenNoSkillTier() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("fast").build());
        AgentSession session = AgentSession.builder()
                .metadata(Map.of(ContextAttributes.SESSION_MODEL_TIER, "deep"))
                .build();

        AgentContext context = AgentContext.builder()
                .session(session)
                .currentIteration(0)
                .build();
        resolver.resolve(context);

        assertEquals("deep", context.getModelTier());
        assertEquals("session_pref", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldUseImplicitDefaultWhenNoTierConfigured() {
        AgentContext context = AgentContext.builder().currentIteration(0).build();
        resolver.resolve(context);

        assertNull(context.getModelTier());
        assertEquals("implicit_default", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldSkipTierResolutionOnNonZeroIteration() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("power").tierForce(true).build());

        AgentContext context = AgentContext.builder().currentIteration(1).build();
        resolver.resolve(context);

        assertNull(context.getModelTier());
    }

    @Test
    void shouldApplyAutoModeTierDefault() {
        when(runtimeConfigService.getAutoModelTier()).thenReturn("power");

        Message autoMsg = Message.builder().role("user").content("auto task")
                .metadata(Map.of(ContextAttributes.AUTO_MODE, true)).build();

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .messages(List.of(autoMsg))
                .build();
        resolver.resolve(context);

        assertEquals("power", context.getModelTier());
        assertEquals("auto_mode_default", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldPublishResolvedTierMetadata() {
        when(modelSelectionService.resolveForTier(any()))
                .thenReturn(new ModelSelectionService.ModelSelection("claude-4-sonnet", "high"));

        AgentContext context = AgentContext.builder().currentIteration(0).build();
        resolver.resolve(context);

        assertEquals("claude-4-sonnet", context.getAttribute(ContextAttributes.MODEL_TIER_MODEL_ID));
        assertEquals("high", context.getAttribute(ContextAttributes.MODEL_TIER_REASONING));
    }

    @Test
    void shouldHandleNullContext() {
        resolver.resolve(null); // should not throw
    }

    // --- Reflection tier resolution ------------------------------------------

    @Test
    void shouldApplyConfiguredReflectionOverrideWhenPriorityFlagIsTrue() {
        // priority=true + configured override beats everything else, even a
        // skill.getReflectionTier() that would otherwise win.
        Skill skill = Skill.builder().name("auto").description("auto")
                .reflectionTier("skill_power").build();
        when(skillComponent.findByName("auto")).thenReturn(java.util.Optional.of(skill));

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_REFLECTION_TIER, "override_power",
                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, Boolean.TRUE,
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "auto")))
                .build();

        resolver.resolve(context);

        assertEquals("override_power", context.getModelTier());
        assertEquals("reflection_override", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldPreferSkillReflectionTierWhenPriorityFlagIsFalse() {
        Skill skill = Skill.builder().name("auto").description("auto")
                .reflectionTier("skill_reflection_tier").build();
        when(skillComponent.findByName("auto")).thenReturn(java.util.Optional.of(skill));

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_REFLECTION_TIER, "override_ignored",
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "auto")))
                .build();

        resolver.resolve(context);

        assertEquals("skill_reflection_tier", context.getModelTier());
        assertEquals("skill_reflection", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldFallBackToConfiguredReflectionOverrideWhenNoSkillReflectionTier() {
        // Skill has no reflectionTier so the second branch falls through to
        // the configured override.
        Skill skill = Skill.builder().name("auto").description("auto").build();
        when(skillComponent.findByName("auto")).thenReturn(java.util.Optional.of(skill));

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_REFLECTION_TIER, "override_power",
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "auto")))
                .build();

        resolver.resolve(context);

        assertEquals("override_power", context.getModelTier());
        assertEquals("reflection_override", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldFallBackToRuntimeReflectionTierWhenNoOverridesPresent() {
        when(runtimeConfigService.getAutoReflectionModelTier()).thenReturn("runtime_tier");
        Skill skill = Skill.builder().name("auto").description("auto").build();
        when(skillComponent.findByName("auto")).thenReturn(java.util.Optional.of(skill));

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "auto")))
                .build();

        resolver.resolve(context);

        assertEquals("runtime_tier", context.getModelTier());
        assertEquals("runtime_reflection", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldFallBackToSkillModelTierWhenNoReflectionOverridesAndNoRuntimeTier() {
        Skill skill = Skill.builder().name("auto").description("auto").modelTier("skill_default").build();
        when(skillComponent.findByName("auto")).thenReturn(java.util.Optional.of(skill));

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "auto")))
                .build();

        resolver.resolve(context);

        assertEquals("skill_default", context.getModelTier());
        assertEquals("skill", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldFallBackToUserPreferenceWhenReflectionHasNoTierSource() {
        // No skill, no override, no runtime tier — should fall back to the
        // user preference (the fallbackTier parameter).
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("user_fallback").build());

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE)))
                .build();

        resolver.resolve(context);

        assertEquals("user_fallback", context.getModelTier());
        assertEquals("user_pref", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldDetectReflectionContextFromLastMessageMetadata() {
        // Drives the Message-metadata branch of isAutoReflectionContext rather
        // than the context-attribute branch.
        Message autoMsg = Message.builder().role("user").content("run")
                .metadata(new HashMap<>(Map.of(ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_REFLECTION_TIER, "msg_override")))
                .build();
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .messages(List.of(autoMsg))
                .build();

        resolver.resolve(context);

        assertEquals("msg_override", context.getModelTier());
        assertEquals("reflection_override", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldReadReflectionPriorityFromMessageMetadataString() {
        // The priority flag is allowed to arrive as a String (e.g. from webhook
        // payloads) and must be parsed. Exercises the String branch of
        // resolveReflectionTierPriority.
        Skill skill = Skill.builder().name("auto").description("auto")
                .reflectionTier("skill_ignored").build();
        when(skillComponent.findByName("auto")).thenReturn(java.util.Optional.of(skill));

        Message autoMsg = Message.builder().role("user").content("run")
                .metadata(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.AUTO_REFLECTION_TIER, "override_via_msg",
                        ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, "true",
                        ContextAttributes.AUTO_RUN_ACTIVE_SKILL, "auto")))
                .build();

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .messages(List.of(autoMsg))
                .build();

        resolver.resolve(context);

        assertEquals("override_via_msg", context.getModelTier());
        assertEquals("reflection_override", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }

    @Test
    void shouldResolveReflectionSkillFromActiveSkillNameAttribute() {
        // Exercises the ACTIVE_SKILL_NAME branch of resolveReflectionSkillName.
        Skill skill = Skill.builder().name("fallback-skill").description("fallback")
                .reflectionTier("fallback_tier").build();
        when(skillComponent.findByName("fallback-skill")).thenReturn(java.util.Optional.of(skill));

        AgentContext context = AgentContext.builder()
                .currentIteration(0)
                .attributes(new HashMap<>(Map.of(
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, Boolean.TRUE,
                        ContextAttributes.ACTIVE_SKILL_NAME, "fallback-skill")))
                .build();

        resolver.resolve(context);

        assertEquals("fallback_tier", context.getModelTier());
        assertEquals("skill_reflection", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
    }
}
