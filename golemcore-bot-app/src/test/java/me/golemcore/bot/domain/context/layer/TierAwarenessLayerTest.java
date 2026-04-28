package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TierAwarenessLayerTest {

    private UserPreferencesService userPreferencesService;
    private TierAwarenessLayer layer;

    @BeforeEach
    void setUp() {
        userPreferencesService = mock(UserPreferencesService.class);
        when(userPreferencesService.getPreferences()).thenReturn(UserPreferences.builder().build());
        layer = new TierAwarenessLayer(userPreferencesService);
    }

    @Test
    void shouldApplyWhenSkillHasTierAndNotForced() {
        Skill skill = Skill.builder().name("test").description("Test").modelTier("power").build();
        AgentContext context = AgentContext.builder().activeSkill(skill).build();

        assertTrue(layer.appliesTo(context));
    }

    @Test
    void shouldNotApplyWhenTierIsForced() {
        when(userPreferencesService.getPreferences())
                .thenReturn(UserPreferences.builder().tierForce(true).build());

        Skill skill = Skill.builder().name("test").description("Test").modelTier("power").build();
        AgentContext context = AgentContext.builder().activeSkill(skill).build();

        assertFalse(layer.appliesTo(context));
    }

    @Test
    void shouldNotApplyWhenNoActiveSkill() {
        assertFalse(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldNotApplyWhenSkillHasNoTier() {
        Skill skill = Skill.builder().name("test").description("Test").build();
        AgentContext context = AgentContext.builder().activeSkill(skill).build();

        assertFalse(layer.appliesTo(context));
    }

    @Test
    void shouldRenderTierAwarenessNote() {
        Skill skill = Skill.builder().name("coding").description("Code").modelTier("reasoning").build();
        AgentContext context = AgentContext.builder().activeSkill(skill).build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Model Tier"));
        assertTrue(result.getContent().contains("coding"));
        assertTrue(result.getContent().contains("reasoning"));
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("tier_awareness", layer.getName());
        assertEquals(60, layer.getOrder());
    }
}
