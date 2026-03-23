package me.golemcore.bot.domain.context.resolution;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void shouldFallBackToUserPrefWhenNoSkillTier() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().modelTier("fast").build());

        AgentContext context = AgentContext.builder().currentIteration(0).build();
        resolver.resolve(context);

        assertEquals("fast", context.getModelTier());
        assertEquals("user_pref", context.getAttribute(ContextAttributes.MODEL_TIER_SOURCE));
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
}
