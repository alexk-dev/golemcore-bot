package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillPipelineSystemTest {

    private static final String CONTENT_RESULT = "result";
    private static final String ATTR_LLM_RESPONSE = ContextAttributes.LLM_RESPONSE;
    private static final String ATTR_PIPELINE_DEPTH = "skill.pipeline.depth";
    private static final String SKILL_EXECUTOR = "executor";

    private SkillComponent skillComponent;
    private SkillPipelineSystem system;

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        system = new SkillPipelineSystem(skillComponent);
    }

    private AgentContext createContext(Skill activeSkill, LlmResponse response) {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .chatId("ch1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .activeSkill(activeSkill)
                .build();
        if (response != null) {
            context.setAttribute(ATTR_LLM_RESPONSE, response);
        }
        return context;
    }

    @Test
    void name() {
        assertEquals("SkillPipelineSystem", system.getName());
    }

    @Test
    void order() {
        assertEquals(55, system.getOrder());
    }

    @Test
    void shouldProcess_noResponse() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        AgentContext ctx = createContext(skill, null);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_responseWithToolCalls() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder()
                .content(CONTENT_RESULT)
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("tc1").name("shell").build()))
                .build();
        AgentContext ctx = createContext(skill, response);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_noActiveSkill() {
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();
        AgentContext ctx = createContext(null, response);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_noNextSkill() {
        Skill skill = Skill.builder().name("a").build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();
        AgentContext ctx = createContext(skill, response);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_explicitTransitionAlreadySet() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();
        AgentContext ctx = createContext(skill, response);
        ctx.setSkillTransitionRequest(me.golemcore.bot.domain.model.SkillTransitionRequest.explicit("c"));

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_validAutoTransition() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();
        AgentContext ctx = createContext(skill, response);

        assertTrue(system.shouldProcess(ctx));
    }

    @Test
    void process_autoTransition() {
        Skill activeSkill = Skill.builder().name("analyzer").nextSkill(SKILL_EXECUTOR).build();
        Skill nextSkill = Skill.builder().name(SKILL_EXECUTOR).available(true).build();
        LlmResponse response = LlmResponse.builder().content("Analysis complete").build();

        when(skillComponent.findByName(SKILL_EXECUTOR)).thenReturn(Optional.of(nextSkill));

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        // Transition target should be set
        var req = ctx.getSkillTransitionRequest();
        assertEquals(SKILL_EXECUTOR, req.targetSkill());
        assertEquals(me.golemcore.bot.domain.model.SkillTransitionReason.SKILL_PIPELINE, req.reason());
        // Pipeline depth incremented
        assertEquals(1, (int) ctx.getAttribute(ATTR_PIPELINE_DEPTH));
        // Response cleared
        assertNull(ctx.getAttribute(ATTR_LLM_RESPONSE));
        // Intermediate response stored in session
        assertEquals(1, ctx.getSession().getMessages().size());
        assertEquals("assistant", ctx.getSession().getMessages().get(0).getRole());
        assertEquals("Analysis complete", ctx.getSession().getMessages().get(0).getContent());
    }

    @Test
    void process_maxDepthReached() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();

        AgentContext ctx = createContext(activeSkill, response);
        ctx.setAttribute(ATTR_PIPELINE_DEPTH, 5);

        system.process(ctx);

        // Should NOT set transition
        assertNull(ctx.getSkillTransitionRequest());
        // Response should still be present
        assertNotNull(ctx.getAttribute(ATTR_LLM_RESPONSE));
    }

    @Test
    void process_nextSkillNotFound() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("nonexistent").build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();

        when(skillComponent.findByName("nonexistent")).thenReturn(Optional.empty());

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        // Should NOT set transition
        assertNull(ctx.getSkillTransitionRequest());
    }

    @Test
    void process_nextSkillUnavailable() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("broken").build();
        Skill brokenSkill = Skill.builder().name("broken").available(false).build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();

        when(skillComponent.findByName("broken")).thenReturn(Optional.of(brokenSkill));

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        assertNull(ctx.getSkillTransitionRequest());
    }

    @Test
    void process_incrementsDepth() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("b").build();
        Skill nextSkill = Skill.builder().name("b").available(true).build();
        LlmResponse response = LlmResponse.builder().content(CONTENT_RESULT).build();

        when(skillComponent.findByName("b")).thenReturn(Optional.of(nextSkill));

        AgentContext ctx = createContext(activeSkill, response);
        ctx.setAttribute(ATTR_PIPELINE_DEPTH, 3);

        system.process(ctx);

        assertEquals(4, (int) ctx.getAttribute(ATTR_PIPELINE_DEPTH));
    }

    @Test
    void process_blankResponseNotStoredInSession() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("b").build();
        Skill nextSkill = Skill.builder().name("b").available(true).build();
        LlmResponse response = LlmResponse.builder().content("  ").build();

        when(skillComponent.findByName("b")).thenReturn(Optional.of(nextSkill));

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        // Blank content should not be stored
        assertEquals(0, ctx.getSession().getMessages().size());
        // But transition should still happen
        var req = ctx.getSkillTransitionRequest();
        assertEquals("b", req.targetSkill());
        assertEquals(me.golemcore.bot.domain.model.SkillTransitionReason.SKILL_PIPELINE, req.reason());
    }
}
