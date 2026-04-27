package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.skills.SkillTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillLayerTest {

    private SkillComponent skillComponent;
    private SkillTemplateEngine templateEngine;
    private SkillLayer layer;

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        templateEngine = mock(SkillTemplateEngine.class);
        layer = new SkillLayer(skillComponent, templateEngine);
    }

    @Test
    void shouldAlwaysApply() {
        assertTrue(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderActiveSkillContent() {
        Skill skill = Skill.builder()
                .name("coding")
                .description("Code helper")
                .content("Write clean Java code.\nUse constructor injection.")
                .build();

        AgentContext context = AgentContext.builder().activeSkill(skill).build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Active Skill: coding"));
        assertTrue(result.getContent().contains("Write clean Java code."));
    }

    @Test
    void shouldRenderSkillVariables() {
        Skill skill = Skill.builder()
                .name("deploy")
                .description("Deploy")
                .content("Deploy to {{ENV}}")
                .resolvedVariables(Map.of("ENV", "staging"))
                .build();

        when(templateEngine.render(eq("Deploy to {{ENV}}"), any())).thenReturn("Deploy to staging");

        AgentContext context = AgentContext.builder().activeSkill(skill).build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.getContent().contains("Deploy to staging"));
    }

    @Test
    void shouldRenderPipelineInfo() {
        Skill skill = Skill.builder()
                .name("analyze")
                .description("Analyze")
                .content("Analyze the codebase.")
                .nextSkill("implement")
                .conditionalNextSkills(Map.of("needs_review", "review"))
                .build();

        AgentContext context = AgentContext.builder().activeSkill(skill).build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.getContent().contains("# Skill Pipeline"));
        assertTrue(result.getContent().contains("Default next: implement"));
        assertTrue(result.getContent().contains("needs_review"));
    }

    @Test
    void shouldRenderSkillsSummaryWhenNoActiveSkill() {
        when(skillComponent.getSkillsSummary()).thenReturn("- **coding**: Code helper\n- **research**: Research");

        AgentContext context = AgentContext.builder().build();
        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Available Skills"));
        assertTrue(result.getContent().contains("coding"));
        assertTrue(result.getContent().contains("skill_transition"));
    }

    @Test
    void shouldSetSkillsSummaryOnContext() {
        when(skillComponent.getSkillsSummary()).thenReturn("- **test**: Test");

        AgentContext context = AgentContext.builder().build();
        layer.assemble(context);

        assertEquals("- **test**: Test", context.getSkillsSummary());
    }

    @Test
    void shouldReturnEmptyWhenNoSkillAndNoSummary() {
        when(skillComponent.getSkillsSummary()).thenReturn("");

        AgentContext context = AgentContext.builder().build();
        ContextLayerResult result = layer.assemble(context);

        assertFalse(result.hasContent());
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("skill", layer.getName());
        assertEquals(40, layer.getOrder());
    }
}
